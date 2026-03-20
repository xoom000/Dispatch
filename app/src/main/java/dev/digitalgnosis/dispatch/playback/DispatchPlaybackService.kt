package dev.digitalgnosis.dispatch.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.EntryPointAccessors
import dev.digitalgnosis.dispatch.audio.PlaybackStateHolder
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.data.VoiceNotificationRepository
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
    private val voiceNotificationRepository: VoiceNotificationRepository by lazy {
        entryPoint.voiceNotificationRepository()
    }
    private val voiceReplyCoordinator: VoiceReplyCoordinator by lazy {
        entryPoint.voiceReplyCoordinator()
    }
    private val playbackState: PlaybackStateHolder by lazy { entryPoint.playbackStateHolder() }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Dedicated thread for ExoPlayer's internal playback looper.
     * Prevents audio glitches when the main thread is busy (UI work, GC, etc.).
     * Pattern: Gramophone GramophonePlaybackService.internalPlaybackThread
     */
    private val playbackThread = HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    /** Tracks how many messages are still in the pipeline (downloading or playing). */
    private val pendingCount = AtomicInteger(0)

    /**
     * Set to true when the user explicitly pauses via earbud tap or UI button.
     * Prevents new incoming messages from auto-resuming playback against user intent.
     * Cleared when the user explicitly presses play.
     */
    @Volatile private var userPaused = false

    /**
     * Messages waiting to play. Streaming DataSources can't be pre-buffered by ExoPlayer
     * because the HTTP POST response is consumed once and discarded. We hold pending
     * messages here and play them one at a time when STATE_ENDED fires.
     */
    private data class PendingMessage(
        val text: String,
        val voice: String,
        val sender: String,
        val traceId: String?,
        val fcmReceiveTime: Long,
    )
    private val messageQueue = ArrayDeque<PendingMessage>()

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

    // ── Lifecycle ───────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        playbackThread.start()

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
            .setPlaybackLooper(playbackThread.looper)
            .build()

        exoPlayer.addListener(PlayerEventListener(
            pendingCount = pendingCount,
            playbackState = playbackState,
            callbacks = object : PlayerEventListener.Callbacks {
                override val messageQueueSize: Int get() = messageQueue.size
                override fun playNextFromQueue() = this@DispatchPlaybackService.playNextFromQueue()
                override fun flushLogs() = this@DispatchPlaybackService.flushLogs()
                override fun onQueueBecameEmpty() {
                    voiceNotificationRepository.setPlayingNotification(null)
                }
            }
        ))
        player = exoPlayer

        // Wrap with EndedWorkaroundPlayer to fix Media3 STATE_ENDED → STATE_IDLE race on seekTo(0).
        // Then wrap with DispatchForwardingPlayer to intercept earbud skip commands for voice reply,
        // and to track user-initiated pause/play so new messages don't auto-resume against user intent.
        val workaroundPlayer = EndedWorkaroundPlayer(exoPlayer)
        val dispatchPlayer = DispatchForwardingPlayer(
            player = workaroundPlayer,
            onVoiceReplyRequested = { startVoiceReply() },
            onUserPaused = { userPaused = true },
            onUserPlayed = { userPaused = false },
        )

        mediaSession = MediaSession.Builder(this, dispatchPlayer).build()

        Timber.i("DispatchPlaybackService: created with Media3 ExoPlayer + MediaSession")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Let MediaSessionService handle its internal binding
        super.onStartCommand(intent, flags, startId)

        // Cancel action: drain queued streaming sentences (sent by StreamingTtsQueue.cancel()).
        // Streaming sentences have fcmReceiveTime == 0L (no FCM timestamp) unlike FCM voice messages.
        if (intent?.action == ACTION_CANCEL_STREAMING) {
            val sizeBefore = messageQueue.size
            messageQueue.removeAll { it.fcmReceiveTime == 0L }
            val drained = sizeBefore - messageQueue.size
            val count = pendingCount.addAndGet(-drained).coerceAtLeast(0)
            Timber.i("DispatchPlaybackService: cancel streaming — drained %d sentences, pending=%d",
                drained, count)
            return START_STICKY
        }

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

        // If player is paused but the user did NOT explicitly pause (e.g. transient state),
        // auto-resume. If the user intentionally paused, respect it — queue the new message
        // but don't restart audio without consent.
        player?.let { p ->
            if (!p.isPlaying && p.playbackState == Player.STATE_READY && !userPaused) {
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
        playbackThread.quitSafely()
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
     * Queue a streaming TTS message. If the player is idle/ended, play immediately.
     * If already playing, hold in messageQueue — STATE_ENDED triggers the next one.
     *
     * We do NOT add multiple streaming items to ExoPlayer's playlist because
     * ExoPlayer eagerly pre-buffers the next item's DataSource, consuming the
     * HTTP streaming response before it's time to play. The response is gone
     * by the time ExoPlayer transitions to it.
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

        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            // Player is free — play immediately
            playStreamingItem(text, voice, sender, traceId, fcmReceiveTime)
        } else {
            // Player is busy — hold for later
            messageQueue.addLast(PendingMessage(text, voice, sender, traceId, fcmReceiveTime))
            Timber.i("[trace:%s] DispatchPlaybackService: queued for later, %d in queue",
                traceId ?: "none", messageQueue.size)
        }
    }

    /**
     * Start streaming a single TTS item NOW. Creates the DataSource and MediaSource,
     * clears stale items, and starts playback.
     */
    @OptIn(UnstableApi::class)
    private fun playStreamingItem(
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

        val dataSourceFactory = DataSource.Factory { TtsStreamDataSource(streamRegistry, streamingClient) }
        val mediaSource = ProgressiveMediaSource.Factory(
            dataSourceFactory,
            RawPcmExtractor.factory(KOKORO_SAMPLE_RATE),
        ).createMediaSource(mediaItem)

        p.clearMediaItems()
        p.addMediaSource(mediaSource)
        p.prepare()
        p.play()

        // Track which notification is currently playing so voice reply targets the right sender.
        // The cursor resets to 0 (newest) on every add(), so without this, a queued message N
        // arriving while message M is playing would cause double-tap to reply to the wrong sender.
        val playingNotification = voiceNotificationRepository.notifications.value
            .firstOrNull { it.sender.equals(sender, ignoreCase = true) }
        voiceNotificationRepository.setPlayingNotification(playingNotification)

        val queueMs = if (fcmReceiveTime > 0) System.currentTimeMillis() - fcmReceiveTime else -1L
        Timber.i("[trace:%s] DispatchPlaybackService: PLAYING stream, pending=%d, queued=%d, fcm->play=%dms",
            traceId ?: "none", pendingCount.get(), messageQueue.size, queueMs)
    }

    /** Play the next message from the queue, if any. Must be called on main thread. */
    private fun playNextFromQueue() {
        val next = messageQueue.removeFirstOrNull() ?: return
        Timber.i("[trace:%s] DispatchPlaybackService: playing next from queue, %d remaining",
            next.traceId ?: "none", messageQueue.size)
        playStreamingItem(next.text, next.voice, next.sender, next.traceId, next.fcmReceiveTime)
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

    // ── Voice Reply ─────────────────────────────────────────────────

    private fun startVoiceReply() {
        val p = player ?: return
        VoiceReplySession(
            context = this,
            player = p,
            voiceNotificationRepository = voiceNotificationRepository,
            voiceReplyCoordinator = voiceReplyCoordinator,
            scope = serviceScope,
            callbacks = object : VoiceReplySession.Callbacks {
                override fun playStatusMessage(text: String) =
                    this@DispatchPlaybackService.playStatusMessage(text)
            }
        ).start()
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
        /** Intent action sent by StreamingTtsQueue.cancel() to drain queued sentences. */
        const val ACTION_CANCEL_STREAMING = "dev.digitalgnosis.dispatch.CANCEL_STREAMING"

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

        private const val CONNECT_TIMEOUT_S = 5L
        private const val READ_TIMEOUT_S = 30L
        private const val WAV_HEADER_SIZE = 44L
        private const val KOKORO_SAMPLE_RATE = 24000
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

        fun createCancelStreamingIntent(context: Context): Intent =
            Intent(context, DispatchPlaybackService::class.java).apply {
                action = ACTION_CANCEL_STREAMING
            }
    }
}
