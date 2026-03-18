package dev.digitalgnosis.dispatch.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.EntryPointAccessors
import dev.digitalgnosis.dispatch.audio.PlaybackStateHolder
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.DispatchMessage
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.fcm.FcmEntryPoint
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media3 playback service for Dispatch voice messages.
 *
 * Replaces the legacy AudioPlaybackService (raw Service + AudioTrack + MediaSessionCompat)
 * with a modern MediaSessionService + ExoPlayer stack.
 *
 * What Media3 gives us for free:
 *   - Audio focus handling (pauses during phone calls)
 *   - Headphone disconnect handling (pauses when unplugged)
 *   - Automatic MediaStyle foreground notification
 *   - Proper Bluetooth/Pixel Buds media button routing via MediaSession
 *   - Playback queue (multiple messages play in FIFO order)
 *   - Audio drain handled internally (no manual polling)
 *   - Android 15/16/17 foreground service compliance
 *
 * Communication flow:
 *   FCM push → DispatchFcmService → startForegroundService(intent) → here
 *   onStartCommand → ExoPlayer opens streaming POST to Kokoro → plays audio as it generates
 *   First audio in ~500ms. No temp file. No waiting for full generation.
 *
 * Earbud control:
 *   - Single tap (play/pause): ExoPlayer handles natively
 *   - Double tap (skip next): intercepted by ForwardingPlayer → voice reply
 *   - Triple tap (skip prev): reserved
 */
class DispatchPlaybackService : MediaSessionService() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
    }

    private val ttsEngine: TtsEngine by lazy { entryPoint.ttsEngine() }
    private val messageRepository: MessageRepository by lazy { entryPoint.messageRepository() }
    private val cmailRepository: CmailRepository by lazy { entryPoint.cmailRepository() }
    private val playbackState: PlaybackStateHolder by lazy { entryPoint.playbackStateHolder() }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    /** Tracks how many messages are still in the pipeline (downloading or playing). */
    private val pendingCount = AtomicInteger(0)

    /** Tap gesture debounce for Pixel Buds. */
    private val tapHandler = android.os.Handler(Looper.getMainLooper())
    private var pendingSingleTap: Runnable? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Longer timeout for streaming — Kokoro may take 10s+ to finish generating. */
    private val streamingClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .build()

    /**
     * Registry of pending stream requests. Keyed by unique stream ID.
     * When ExoPlayer opens a tts:// URI, the DataSource looks up the
     * request params here and POSTs to Kokoro's streaming endpoint.
     */
    private val streamRegistry = ConcurrentHashMap<String, StreamRequest>()

    private data class StreamRequest(
        val text: String,
        val voice: String,
        val speed: Float,
        val traceId: String?,
        val startTime: Long = System.currentTimeMillis(),
    )

    // ── Lifecycle ───────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        createDownloadNotificationChannel()

        // Configure notification provider to use our notification ID
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(PLAYBACK_CHANNEL_ID)
            .build()
        setMediaNotificationProvider(notificationProvider)

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(PlayerEventListener())
        player = exoPlayer

        // ForwardingPlayer intercepts skip commands for voice reply
        val dispatchPlayer = DispatchForwardingPlayer(exoPlayer)

        mediaSession = MediaSession.Builder(this, dispatchPlayer).build()

        Timber.i("DispatchPlaybackService: created with Media3 ExoPlayer + MediaSession")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Let MediaSessionService handle its internal binding
        super.onStartCommand(intent, flags, startId)

        val text = intent?.getStringExtra(EXTRA_TEXT)
        val voice = intent?.getStringExtra(EXTRA_VOICE)
        val sender = intent?.getStringExtra(EXTRA_SENDER) ?: "unknown"
        val traceId = intent?.getStringExtra(EXTRA_TRACE_ID)?.ifBlank { null }
        val fcmReceiveTime = intent?.getLongExtra(EXTRA_FCM_RECEIVE_TIME, 0L) ?: 0L

        if (text == null || voice == null) {
            Timber.w("DispatchPlaybackService: missing text or voice, posting idle notification")
            // Post foreground notification immediately to satisfy startForegroundService requirement
            startForeground(NOTIFICATION_ID, buildDownloadNotification("Dispatch ready"))
            return START_STICKY
        }

        val count = pendingCount.incrementAndGet()
        val serviceLatencyMs = if (fcmReceiveTime > 0) System.currentTimeMillis() - fcmReceiveTime else -1L
        Timber.i("[trace:%s] DispatchPlaybackService: sender=%s, voice=%s, text=%d chars, pending=%d, fcm->service=%dms",
            traceId ?: "none", sender, voice, text.length, count, serviceLatencyMs)

        // Promote to foreground IMMEDIATELY.
        // MediaSessionService will take over notification once ExoPlayer has media items.
        startForeground(NOTIFICATION_ID, buildDownloadNotification("Streaming from $sender..."))

        // If player is paused from a stale earbud tap, auto-resume for new message
        player?.let { p ->
            if (!p.isPlaying && p.playbackState == Player.STATE_READY) {
                Timber.i("DispatchPlaybackService: stale pause detected — auto-resuming")
                p.play()
            }
        }

        val messageText = intent.getStringExtra(EXTRA_MESSAGE) ?: text
        playbackState.onPlaybackStarted(sender, messageText, voice)
        playbackState.onQueueCountChanged(count)

        // Stream audio — ExoPlayer POSTs to Kokoro and plays as bytes arrive.
        // No download step. First audio in ~500ms.
        queueStreamingItem(text, voice, sender, traceId, fcmReceiveTime)

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        cleanTempFiles()
        Timber.i("DispatchPlaybackService: destroyed")
        super.onDestroy()
    }

    // ── Audio Download ──────────────────────────────────────────────

    /**
     * Download WAV from Oasis GPU TTS server.
     * Retries once for Tailscale cold-start scenarios.
     * Saves complete WAV (with header) to temp file — ExoPlayer handles the format.
     */
    private fun downloadWav(text: String, voice: String, traceId: String?): File? {
        for (attempt in 1..MAX_RETRIES) {
            val result = attemptDownload(text, voice, traceId, attempt)
            if (result != null) return result

            if (attempt < MAX_RETRIES) {
                Timber.i("DispatchPlaybackService: attempt %d failed, retrying in %dms", attempt, RETRY_DELAY_MS)
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    private fun attemptDownload(text: String, voice: String, traceId: String?, attempt: Int): File? {
        val start = System.currentTimeMillis()
        try {
            val payload = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("speed", 1.0)
            }

            val requestBuilder = Request.Builder()
                .url(TailscaleConfig.TTS_SERVER)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Connection", "close")

            traceId?.let { requestBuilder.header("X-Trace-Id", it) }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val connectMs = System.currentTimeMillis() - start

            if (!response.isSuccessful) {
                Timber.w("DispatchPlaybackService: HTTP %d [attempt %d]", response.code, attempt)
                response.close()
                return null
            }

            val body = response.body ?: run {
                Timber.w("DispatchPlaybackService: empty response body [attempt %d]", attempt)
                response.close()
                return null
            }

            // Save WAV to temp file — ExoPlayer reads it natively
            val tempFile = File(cacheDir, "dispatch_${System.currentTimeMillis()}.wav")
            tempFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    input.copyTo(out)
                }
            }
            response.close()

            val downloadMs = System.currentTimeMillis() - start
            Timber.i("DispatchPlaybackService: WAV saved (%d bytes, %dms) [attempt %d]",
                tempFile.length(), downloadMs, attempt)

            if (tempFile.length() <= WAV_HEADER_SIZE) {
                Timber.w("DispatchPlaybackService: WAV too small (%d bytes)", tempFile.length())
                tempFile.delete()
                return null
            }

            return tempFile
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Timber.w(e, "DispatchPlaybackService: download FAILED after %dms [attempt %d]", elapsed, attempt)
            return null
        }
    }

    // ── ExoPlayer Queue ─────────────────────────────────────────────

    /**
     * Add downloaded WAV to ExoPlayer playlist. Must be called on main thread.
     */
    private fun queueMediaItem(wavFile: File, sender: String, traceId: String?, fcmReceiveTime: Long) {
        val p = player ?: return

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(wavFile))
            .setMediaId(traceId ?: wavFile.name)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Dispatch from $sender")
                    .setArtist(sender)
                    .build()
            )
            .build()

        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            // Clear stale items from previous playback, then start fresh
            p.clearMediaItems()
            p.addMediaItem(mediaItem)
            p.prepare()
            p.play()
        } else {
            p.addMediaItem(mediaItem)
        }

        val queueMs = if (fcmReceiveTime > 0) System.currentTimeMillis() - fcmReceiveTime else -1L
        Timber.i("[trace:%s] DispatchPlaybackService: queued MediaItem, total=%d items, fcm->queue=%dms",
            traceId ?: "none", p.mediaItemCount, queueMs)
    }

    // ── Streaming TTS Playback ─────────────────────────────────────

    /**
     * Queue a streaming TTS item. ExoPlayer opens the connection itself,
     * POSTs to Kokoro's /api/tts/stream endpoint, and plays audio as
     * chunks arrive. First audio in ~500ms instead of 2-5s.
     *
     * Must be called on main thread.
     */
    @OptIn(UnstableApi::class)
    private fun queueStreamingItem(
        text: String,
        voice: String,
        sender: String,
        traceId: String?,
        fcmReceiveTime: Long,
    ) {
        val p = player ?: return

        val streamId = UUID.randomUUID().toString()
        streamRegistry[streamId] = StreamRequest(text, voice, 1.0f, traceId)

        val mediaItem = MediaItem.Builder()
            .setUri("tts://stream/$streamId")
            .setMediaId(traceId ?: streamId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Dispatch from $sender")
                    .setArtist(sender)
                    .build()
            )
            .build()

        val dataSourceFactory = DataSource.Factory { TtsStreamDataSource() }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            p.clearMediaItems()
            p.addMediaSource(mediaSource)
            p.prepare()
            p.play()
        } else {
            p.addMediaSource(mediaSource)
        }

        val queueMs = if (fcmReceiveTime > 0) System.currentTimeMillis() - fcmReceiveTime else -1L
        Timber.i("[trace:%s] DispatchPlaybackService: queued STREAM item, pending=%d, fcm->queue=%dms",
            traceId ?: "none", p.mediaItemCount, queueMs)
    }

    /**
     * Custom DataSource that POSTs to Kokoro's streaming TTS endpoint
     * and feeds audio bytes directly to ExoPlayer as they generate.
     *
     * ExoPlayer calls open() → we POST to Kokoro, get a chunked response.
     * ExoPlayer calls read() → we hand it bytes from the response stream.
     * ExoPlayer starts playback as soon as it has the WAV header + first PCM chunk.
     */
    @OptIn(UnstableApi::class)
    private inner class TtsStreamDataSource : BaseDataSource(/* isNetwork= */ true) {

        private var response: okhttp3.Response? = null
        private var inputStream: InputStream? = null
        private var streamId: String? = null

        override fun open(dataSpec: DataSpec): Long {
            streamId = dataSpec.uri.lastPathSegment
                ?: throw IOException("Missing stream ID in URI: ${dataSpec.uri}")

            val req = streamRegistry.remove(streamId!!)
                ?: throw IOException("Unknown stream ID: $streamId")

            val payload = JSONObject().apply {
                put("text", req.text)
                put("voice", req.voice)
                put("speed", req.speed.toDouble())
            }

            val requestBuilder = Request.Builder()
                .url(TailscaleConfig.TTS_STREAM_SERVER)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            req.traceId?.let { requestBuilder.header("X-Trace-Id", it) }

            val firstChunkMs = System.currentTimeMillis() - req.startTime
            Timber.i("[trace:%s] TtsStreamDataSource: opening POST to Kokoro, setup=%dms",
                req.traceId ?: "none", firstChunkMs)

            response = streamingClient.newCall(requestBuilder.build()).execute()

            if (!response!!.isSuccessful) {
                val code = response!!.code
                response!!.close()
                throw IOException("Kokoro stream returned HTTP $code")
            }

            inputStream = response!!.body?.byteStream()
                ?: throw IOException("Kokoro stream returned empty body")

            val connectMs = System.currentTimeMillis() - req.startTime
            Timber.i("[trace:%s] TtsStreamDataSource: connected, first bytes arriving, total=%dms",
                req.traceId ?: "none", connectMs)

            // LENGTH_UNSET = streaming/unknown length — ExoPlayer won't expect a fixed size
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val stream = inputStream ?: return C.RESULT_END_OF_INPUT
            val bytesRead = stream.read(buffer, offset, length)
            return if (bytesRead == -1) C.RESULT_END_OF_INPUT else bytesRead
        }

        override fun getUri(): Uri? = streamId?.let { Uri.parse("tts://stream/$it") }

        override fun close() {
            try { inputStream?.close() } catch (_: Exception) {}
            try { response?.close() } catch (_: Exception) {}
            inputStream = null
            response = null
        }
    }

    /** Called when a message finishes (playback complete or fallback done). */
    private fun onMessageComplete(traceId: String?, fcmReceiveTime: Long) {
        val remaining = pendingCount.decrementAndGet()
        val pipelineMs = if (fcmReceiveTime > 0) System.currentTimeMillis() - fcmReceiveTime else -1L
        Timber.i("[trace:%s] DispatchPlaybackService: COMPLETE, pipeline=%dms, pending=%d",
            traceId ?: "none", pipelineMs, remaining)

        if (remaining <= 0) {
            Timber.i("DispatchPlaybackService: all messages complete, staying alive for MediaSession")
            flushLogs()
            playbackState.onQueueEmpty()
        } else {
            playbackState.onQueueCountChanged(remaining)
        }
    }

    // ── Player Event Listener ───────────────────────────────────────

    private inner class PlayerEventListener : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Previous item finished, next started automatically
                val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                Timber.i("DispatchPlaybackService: media transition, pending=%d", remaining)
                playbackState.onQueueCountChanged(remaining)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    // Decrement for the final item — onMediaItemTransition only fires BETWEEN items
                    val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                    Timber.i("DispatchPlaybackService: playback ended (queue empty), pending=%d", remaining)
                    if (remaining <= 0) {
                        this@DispatchPlaybackService.playbackState.onQueueEmpty()
                    }
                    flushLogs()
                    // Temp file cleanup deferred to queueMediaItem (when next message arrives)
                    // or onDestroy. Cleaning here nukes files ExoPlayer's playlist still references.
                }
                Player.STATE_READY -> {
                    Timber.d("DispatchPlaybackService: player ready")
                }
                Player.STATE_BUFFERING -> {
                    Timber.d("DispatchPlaybackService: player buffering")
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "DispatchPlaybackService: ExoPlayer error — %s", error.message)
            val p = player ?: return
            if (p.hasNextMediaItem()) {
                Timber.i("DispatchPlaybackService: skipping failed item, trying next")
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            } else {
                pendingCount.set(0)
                this@DispatchPlaybackService.playbackState.onQueueEmpty()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                this@DispatchPlaybackService.playbackState.onPlaybackResumed()
            } else {
                this@DispatchPlaybackService.playbackState.onPlaybackPaused()
            }
        }
    }

    // ── ForwardingPlayer (Earbud Control) ───────────────────────────

    /**
     * Intercepts skip commands for custom behavior:
     * - seekToNext → voice reply (double tap on Pixel Buds)
     * - seekToPrevious → reserved (triple tap)
     * - play/pause → handled by ExoPlayer natively
     */
    private inner class DispatchForwardingPlayer(
        player: ExoPlayer
    ) : ForwardingPlayer(player) {

        override fun seekToNext() {
            Timber.i("MediaSession: double tap — starting voice reply")
            startVoiceReply()
        }

        override fun seekToPrevious() {
            Timber.i("MediaSession: triple tap — reserved")
            // Reserved for future use
        }
    }

    // ── Voice Reply ─────────────────────────────────────────────────

    private fun startVoiceReply() {
        val lastMessage = messageRepository.getMessageAtCursor()
        val rawSender = lastMessage?.sender
        val targetDepartment = if (rawSender.isNullOrBlank() || rawSender == "dispatch") {
            "boardroom"
        } else {
            rawSender
        }
        val threadId = lastMessage?.threadId

        Timber.i("VoiceReply: starting reply to %s (thread=%s)", targetDepartment, threadId ?: "none")

        // Play audio cue
        playStatusMessage("Reply to $targetDepartment. Go ahead.")

        // Launch speech recognition after cue plays
        android.os.Handler(mainLooper).postDelayed({
            launchSpeechRecognition(targetDepartment, threadId)
        }, VOICE_CUE_DELAY_MS)
    }

    private fun launchSpeechRecognition(targetDepartment: String, threadId: String?) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Timber.e("VoiceReply: SpeechRecognizer not available")
            playStatusMessage("Voice recognition not available")
            return
        }

        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Timber.i("VoiceReply: mic is hot")
                playListeningBeep()
            }
            override fun onBeginningOfSpeech() { Timber.i("VoiceReply: speech detected") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Timber.i("VoiceReply: end of speech") }

            override fun onError(error: Int) {
                val msg = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    else -> "Recognition error $error"
                }
                Timber.w("VoiceReply: error %d — %s", error, msg)
                playStatusMessage(msg)
                recognizer.destroy()
            }

            override fun onResults(results: android.os.Bundle?) {
                val transcription = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (transcription.isNullOrBlank()) {
                    Timber.w("VoiceReply: empty transcription")
                    playStatusMessage("Didn't catch that")
                    recognizer.destroy()
                    return
                }

                Timber.i("VoiceReply: transcribed %d chars -> %s", transcription.length, targetDepartment)
                sendVoiceReply(targetDepartment, transcription, threadId)
                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
    }

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
                if (result.isSuccess) {
                    val res = result.getOrThrow()
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
     * Play a brief status message through the TTS pipeline.
     * Downloads WAV from GPU TTS and queues it on ExoPlayer.
     */
    private fun playStatusMessage(text: String) {
        Timber.i("DispatchPlaybackService: status — %s", text)
        serviceScope.launch(Dispatchers.IO) {
            val wavFile = downloadWav(text, STATUS_VOICE, null)
            if (wavFile != null) {
                withContext(Dispatchers.Main) {
                    queueMediaItem(wavFile, "system", null, 0L)
                }
            } else {
                Timber.w("DispatchPlaybackService: GPU failed for status, falling back to Piper")
                ttsEngine.speakBlocking("system", text)
            }
        }
    }

    // ── Notifications ───────────────────────────────────────────────

    /**
     * Temporary notification shown during WAV download, before ExoPlayer has media items.
     * MediaSessionService's auto-notification replaces this once the player starts.
     */
    private fun buildDownloadNotification(text: String): android.app.Notification {
        return NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dispatch")
            .setContentText(text)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createDownloadNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Dispatch Downloading",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while downloading dispatch audio"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        // Also create the playback channel for MediaSessionService auto-notification
        val playbackChannel = NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "Dispatch Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while playing dispatch audio"
            setShowBadge(false)
        }
        nm.createNotificationChannel(playbackChannel)
    }

    private fun playListeningBeep() {
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
            android.os.Handler(mainLooper).postDelayed({ toneGen.release() }, 300)
        } catch (e: Exception) {
            Timber.w(e, "VoiceReply: beep failed (non-fatal)")
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    private fun flushLogs() {
        try {
            if (FileLogTree.isInitialized()) {
                FileLogTree.getInstance().flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "DispatchPlaybackService: log flush failed")
        }
    }

    /** Delete temp WAV files from cache. */
    private fun cleanTempFiles() {
        try {
            cacheDir.listFiles()?.filter { it.name.startsWith("dispatch_") && it.name.endsWith(".wav") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Timber.w(e, "DispatchPlaybackService: temp file cleanup failed")
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TRACE_ID = "trace_id"
        const val EXTRA_FCM_RECEIVE_TIME = "fcm_receive_time"

        private const val NOTIFICATION_ID = 9002
        private const val DOWNLOAD_CHANNEL_ID = "dispatch_download"
        private const val PLAYBACK_CHANNEL_ID = "dispatch_playback"
        private const val STATUS_VOICE = "am_puck"
        private const val VOICE_CUE_DELAY_MS = 2500L

        private const val CONNECT_TIMEOUT_S = 5L
        private const val READ_TIMEOUT_S = 30L
        private const val WAV_HEADER_SIZE = 44L
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L

        fun createIntent(
            context: Context,
            text: String,
            voice: String,
            sender: String,
            message: String,
            traceId: String? = null,
            fcmReceiveTime: Long = 0L,
        ): Intent = Intent(context, DispatchPlaybackService::class.java).apply {
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_VOICE, voice)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_TRACE_ID, traceId ?: "")
            putExtra(EXTRA_FCM_RECEIVE_TIME, fcmReceiveTime)
        }
    }
}
