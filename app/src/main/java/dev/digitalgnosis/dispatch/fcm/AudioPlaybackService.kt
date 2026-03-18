package dev.digitalgnosis.dispatch.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dagger.hilt.android.EntryPointAccessors
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.CmailSendResult
import dev.digitalgnosis.dispatch.data.DispatchMessage
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for reliable background audio playback with MediaSession
 * integration for Pixel Buds / Bluetooth headphone control.
 *
 * FCM data messages get ~10 seconds of execution time. That's not enough
 * for GPU TTS download + playback. This service promotes to foreground
 * immediately (using the high-priority FCM exemption), holds the process
 * alive through the entire audio pipeline.
 *
 * MediaSession behavior:
 *   - Created and activated on first message (onCreate)
 *   - Stays active after playback ends so Pixel Buds taps route here
 *   - Single tap (play/pause) -> pause/resume audio (only when media is active)
 *   - Double tap (skip next) -> voice reply to last message sender via speech recognition
 *   - Triple tap (skip prev) -> reserved for future (agent targeting)
 *   - Service stays alive after queue empties (idle timeout in Phase 3d)
 *
 * Handles multiple rapid messages correctly:
 *   - Tracks pending message count with AtomicInteger
 *   - Audio plays in FIFO order (serial download + serial playback)
 *   - Piper fallback is serialized on the same playback executor (no overlap)
 *   - Notification updates to reflect current state
 *
 * Android 14+ requires foregroundServiceType="mediaPlayback" in manifest.
 */
class AudioPlaybackService : Service() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
    }

    private val audioStreamClient: AudioStreamClient by lazy { entryPoint.audioStreamClient() }
    private val ttsEngine: TtsEngine by lazy { entryPoint.ttsEngine() }
    private val messageRepository: MessageRepository by lazy { entryPoint.messageRepository() }
    private val cmailRepository: CmailRepository by lazy { entryPoint.cmailRepository() }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Tracks how many messages are still in the pipeline (downloading or playing).
     * Notification updates when this changes. Service no longer stops at zero —
     * it stays alive for MediaSession (Pixel Buds tap routing).
     */
    private val pendingCount = AtomicInteger(0)

    /** MediaSession for Bluetooth headphone button routing. */
    private var mediaSession: MediaSessionCompat? = null

    /**
     * Tap gesture debounce handler.
     *
     * Pixel Buds (and most BT headphones) send PLAY_PAUSE before they know
     * if the user is double-tapping. So a double tap fires:
     *   1. onPlay/onPause (first tap)
     *   2. onSkipToNext (double tap detected by firmware)
     *
     * Without debouncing, both actions execute — replay starts AND voice reply starts.
     *
     * Solution: buffer the first tap for TAP_DEBOUNCE_MS. If a higher-priority
     * gesture (skip next/prev) arrives within that window, cancel the single tap
     * and execute the double/triple tap action instead.
     */
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSingleTap: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        Timber.i("AudioPlaybackService: created with MediaSession")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        val voice = intent?.getStringExtra(EXTRA_VOICE)
        val sender = intent?.getStringExtra(EXTRA_SENDER) ?: "unknown"

        if (text == null || voice == null) {
            Timber.w("AudioPlaybackService: missing text or voice, ignoring")
            // Don't stopSelf — service stays alive for MediaSession
            // Still need foreground though, so post idle notification
            startForeground(FOREGROUND_NOTIFICATION_ID, buildIdleNotification())
            return START_STICKY
        }

        val count = pendingCount.incrementAndGet()
        Timber.i("AudioPlaybackService: message from sender=%s, voice=%s, text=%d chars, pending=%d",
            sender, voice, text.length, count)

        // If audio is paused (stale earbud tap), new incoming message auto-resumes.
        // Prevents the queue from blocking forever behind a forgotten pause.
        if (audioStreamClient.isPaused) {
            Timber.i("AudioPlaybackService: stale pause detected — auto-resuming for new message")
            audioStreamClient.resumePlayback()
        }

        // Promote to foreground IMMEDIATELY — this is what keeps us alive
        startForeground(FOREGROUND_NOTIFICATION_ID, buildPlayingNotification(sender, count))

        // Update MediaSession state to playing
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

        // Do the audio work on AudioStreamClient's existing executors.
        // Downloads are serial (FIFO order). Playback is serial.
        // The onComplete sentinel fires only after audio finishes.
        val messageText = intent.getStringExtra(EXTRA_MESSAGE) ?: text
        audioStreamClient.streamAsync(
            text = text,
            voice = voice,
            onComplete = {
                val remaining = pendingCount.decrementAndGet()
                Timber.i("AudioPlaybackService: message complete, pending=%d", remaining)

                if (remaining <= 0) {
                    // All messages done — update to idle state but KEEP SERVICE ALIVE.
                    // MediaSession stays active so Pixel Buds taps route to Dispatch.
                    // Idle timeout (T17) will handle eventual teardown in Phase 3d.
                    Timber.i("AudioPlaybackService: all messages complete, staying alive for MediaSession")
                    flushLogs()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification(buildIdleNotification())
                } else {
                    // More messages queued — update notification count
                    Timber.i("AudioPlaybackService: %d message(s) still pending", remaining)
                    updateNotification(buildPlayingNotification("Dispatch", remaining))
                }
            },
            onFailure = {
                // This runs ON the playback executor (serialized with GPU audio).
                // Use speakBlocking so it blocks until Piper finishes.
                Timber.w("AudioPlaybackService: GPU stream failed, falling back to Piper (blocking)")
                ttsEngine.speakBlocking(sender, messageText)
            }
        )

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        Timber.i("AudioPlaybackService: destroyed, MediaSession released")
        super.onDestroy()
    }

    // ── MediaSession ────────────────────────────────────────────────

    /**
     * Initialize MediaSession for Bluetooth headphone button routing.
     *
     * After Dispatch plays audio, it becomes the active media session owner.
     * Pixel Buds (and any AVRCP-compliant BT headphones) will route tap
     * gestures here until another app plays audio.
     */
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Timber.i("MediaSession: onPlay — buffering as single tap")
                    bufferSingleTap()
                }

                override fun onPause() {
                    Timber.i("MediaSession: onPause — buffering as single tap")
                    bufferSingleTap()
                }

                override fun onSkipToNext() {
                    Timber.i("MediaSession: onSkipToNext — double tap confirmed")
                    cancelPendingSingleTap()
                    startVoiceReply()
                }

                override fun onSkipToPrevious() {
                    Timber.i("MediaSession: onSkipToPrevious — triple tap confirmed")
                    cancelPendingSingleTap()
                    // Reserved for future use (agent targeting, etc.)
                }
            })

            // Accept media button events and transport control commands
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            isActive = true
        }

        // Set initial state so system recognizes us as a media session
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
        Timber.i("MediaSession: initialized, active, ready for button events")
    }

    /**
     * Buffer a single tap — delay execution to see if a double/triple tap follows.
     * If onSkipToNext/onSkipToPrevious fires within the debounce window,
     * the single tap is cancelled and the multi-tap action runs instead.
     */
    private fun bufferSingleTap() {
        cancelPendingSingleTap()
        val action = Runnable {
            Timber.i("MediaSession: single tap confirmed (no follow-up within %dms)", TAP_DEBOUNCE_MS)
            handlePlayPause()
            pendingSingleTap = null
        }
        pendingSingleTap = action
        tapHandler.postDelayed(action, TAP_DEBOUNCE_MS)
    }

    /** Cancel any pending single-tap action (called when double/triple tap arrives). */
    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let {
            tapHandler.removeCallbacks(it)
            Timber.d("MediaSession: single tap cancelled — multi-tap detected")
            pendingSingleTap = null
        }
    }

    /**
     * Single tap — play/pause toggle (only when media is active).
     *
     * If audio is currently playing → pause it.
     * If audio is paused → resume it.
     * If idle (nothing playing) → ignore. No replay.
     */
    private fun handlePlayPause() {
        when {
            audioStreamClient.isPaused -> {
                Timber.i("MediaSession: resuming paused audio")
                audioStreamClient.resumePlayback()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                updateNotification(buildPlayingNotification("Resumed", 1))
            }
            audioStreamClient.isPlaying -> {
                Timber.i("MediaSession: pausing audio")
                audioStreamClient.pausePlayback()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                updateNotification(buildMediaNotification(
                    title = "Dispatch",
                    text = "Paused — tap to resume",
                    ongoing = true
                ))
            }
            else -> {
                Timber.i("MediaSession: idle, single tap ignored (no active media)")
            }
        }
    }

    /**
     * Double tap — start voice reply to the sender of the last played message.
     *
     * Uses Android SpeechRecognizer to transcribe speech, then sends the
     * result via File Bridge → cmail to the original sender's department.
     */
    private fun startVoiceReply() {
        val lastMessage = messageRepository.getMessageAtCursor()
        // Filter out "dispatch" sender — namespace collision means many messages
        // have sender="dispatch" (the CLI tool, not a real department).
        // Fall back to boardroom (Nigel's default) if sender is dispatch or missing.
        val rawSender = lastMessage?.sender
        val targetDepartment = if (rawSender.isNullOrBlank() || rawSender == "dispatch") {
            "boardroom"
        } else {
            rawSender
        }
        val threadId = lastMessage?.threadId

        Timber.i("MediaSession: starting voice reply to %s (thread=%s)",
            targetDepartment, threadId ?: "none")

        // Play audio cue so Nigel knows the mic is hot
        playStatusMessage("Reply to $targetDepartment. Go ahead.")

        // Launch speech recognition after a brief delay for the cue to play
        // Handler posts to main thread (required for SpeechRecognizer)
        android.os.Handler(mainLooper).postDelayed({
            launchSpeechRecognition(targetDepartment, threadId)
        }, VOICE_CUE_DELAY_MS)
    }

    /**
     * Start Android SpeechRecognizer to capture voice input.
     * Must be called on the main thread.
     */
    private fun launchSpeechRecognition(targetDepartment: String, threadId: String?) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Timber.e("MediaSession: SpeechRecognizer not available on this device")
            playStatusMessage("Voice recognition not available")
            return
        }

        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Timber.i("VoiceReply: mic is hot — playing beep")
                playListeningBeep()
            }

            override fun onBeginningOfSpeech() {
                Timber.i("VoiceReply: speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Timber.i("VoiceReply: end of speech, processing...")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    else -> "Recognition error $error"
                }
                Timber.w("VoiceReply: error %d — %s", error, errorMsg)
                playStatusMessage(errorMsg)
                recognizer.destroy()
            }

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(
                    android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                )
                val transcription = matches?.firstOrNull()

                if (transcription.isNullOrBlank()) {
                    Timber.w("VoiceReply: empty transcription")
                    playStatusMessage("Didn't catch that")
                    recognizer.destroy()
                    return
                }

                Timber.i("VoiceReply: transcribed %d chars -> sending to %s",
                    transcription.length, targetDepartment)

                sendVoiceReply(targetDepartment, transcription, threadId)
                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.startListening(intent)
    }

    /**
     * Send transcribed voice reply via File Bridge → cmail.
     * Runs the network call on a background thread via Coroutines.
     */
    private fun sendVoiceReply(department: String, message: String, threadId: String?) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val result = cmailRepository.sendCmail(
                    department = department,
                    message = message,
                    subject = "Voice reply from Nigel",
                    invoke = true,
                    threadId = threadId,
                )
                Timber.i("VoiceReply: sent to %s — %s", department, result)

                if (result.isSuccess) {
                    val res = result.getOrThrow()
                    // Store as outgoing message in repository
                    messageRepository.addMessage(DispatchMessage(
                        sender = "You",
                        message = message,
                        priority = "normal",
                        timestamp = "",
                        isOutgoing = true,
                        targetDepartment = department,
                        invoked = res.invoked,
                        invokedDepartment = if (res.invoked) (res.department ?: department) else null,
                        sessionId = res.sessionId,
                        threadId = threadId,
                    ))

                    playStatusMessage("Sent to $department")
                } else {
                    playStatusMessage("Failed to send reply")
                }
            } catch (e: Exception) {
                Timber.e(e, "VoiceReply: failed to send to %s", department)
                playStatusMessage("Reply error")
            }
        }
    }

    /**
     * Play a brief status message (e.g., "no messages", "sent to engineering").
     * Uses a neutral voice through the normal audio pipeline.
     */
    private fun playStatusMessage(text: String) {
        Timber.i("MediaSession: status — %s", text)

        audioStreamClient.streamAsync(
            text = text,
            voice = STATUS_VOICE,
            onComplete = {
                Timber.d("MediaSession: status message complete")
            },
            onFailure = {
                Timber.w("MediaSession: GPU failed for status, falling back to Piper")
                ttsEngine.speakBlocking("system", text)
            }
        )
    }

    /**
     * Update MediaSession playback state. This tells Android (and Bluetooth
     * devices) what actions we currently support and whether we're playing.
     */
    private fun updatePlaybackState(state: Int) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    // ── Notifications ───────────────────────────────────────────────

    /**
     * Build MediaStyle notification for active playback.
     * Shows transport controls and links to MediaSession.
     */
    private fun buildPlayingNotification(sender: String, pendingCount: Int): android.app.Notification {
        val text = if (pendingCount > 1) {
            "Playing messages ($pendingCount queued)"
        } else {
            "Playing message from $sender"
        }

        return buildMediaNotification(
            title = "Dispatch",
            text = text,
            ongoing = true
        )
    }

    /**
     * Build MediaStyle notification for idle state (no audio playing).
     * Service stays alive for MediaSession — Pixel Buds taps still work.
     */
    private fun buildIdleNotification(): android.app.Notification {
        return buildMediaNotification(
            title = "Dispatch",
            text = "Double-tap earbuds to voice reply",
            ongoing = true
        )
    }

    /**
     * Core notification builder using MediaStyle with session token.
     * This enables lock screen controls and Bluetooth device integration.
     */
    private fun buildMediaNotification(
        title: String,
        text: String,
        ongoing: Boolean
    ): android.app.Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Attach MediaSession token for Bluetooth device integration
        mediaSession?.sessionToken?.let { token ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(token)
            )
        }

        return builder.build()
    }

    private fun updateNotification(notification: android.app.Notification) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.w(e, "AudioPlaybackService: notification update failed")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Dispatch is playing or listening for earbud controls"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    // ── Utility ─────────────────────────────────────────────────────

    /**
     * Play a short beep tone to signal that the mic is listening.
     * Uses ToneGenerator for instant, lightweight audio feedback.
     */
    private fun playListeningBeep() {
        try {
            val toneGen = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC,
                80 // volume (0-100)
            )
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200) // 200ms beep
            // Release after tone completes
            android.os.Handler(mainLooper).postDelayed({ toneGen.release() }, 300)
        } catch (e: Exception) {
            Timber.w(e, "VoiceReply: beep failed (non-fatal)")
        }
    }

    private fun flushLogs() {
        try {
            if (FileLogTree.isInitialized()) {
                FileLogTree.getInstance().flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioPlaybackService: log flush failed")
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "dispatch_playback"
        private const val FOREGROUND_NOTIFICATION_ID = 9001
        private const val MEDIA_SESSION_TAG = "DispatchMediaSession"
        private const val STATUS_VOICE = "am_puck" // Neutral voice for system status messages
        private const val VOICE_CUE_DELAY_MS = 2500L // Wait for "Reply to X" cue to play
        private const val TAP_DEBOUNCE_MS = 600L // Wait for double/triple tap before executing single (Pixel Buds need >400ms)

        fun createIntent(
            context: Context,
            text: String,
            voice: String,
            sender: String,
            message: String
        ): Intent = Intent(context, AudioPlaybackService::class.java).apply {
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_VOICE, voice)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, message)
        }
    }
}
