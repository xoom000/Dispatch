package dev.digitalgnosis.dispatch.network

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays TTS audio from Oasis GPU server.
 *
 * Two-stage pipeline:
 *   1. Download (parallel) — multiple WAVs can download simultaneously
 *   2. Playback (serial)  — one AudioTrack at a time, FIFO order
 *
 * Think of it like a photo line: people arrive (download) whenever,
 * but they go through the camera (speaker) one at a time in order.
 *
 * Holds a PARTIAL_WAKE_LOCK during playback to prevent Android
 * from killing the background process mid-sentence.
 *
 * WAV format: 16-bit PCM, 24kHz, mono.
 */
@Singleton
class AudioStreamClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Single-thread download executor — preserves message ordering.
     * Messages download and play in FIFO arrival order.
     * (Was 3-thread pool, but that caused out-of-order playback
     * when shorter messages downloaded faster than longer ones.)
     */
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    /** Single thread that plays PCM buffers in FIFO order. */
    private val playbackExecutor = Executors.newSingleThreadExecutor()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Reference to the currently playing AudioTrack, for skip/cancel support.
     * Set before play, cleared after release. Volatile for cross-thread visibility
     * (MediaSession callback thread may call skip() while playback thread writes).
     */
    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    /**
     * Skip flag — when true, playPcm() stops writing chunks and exits early.
     * Set by skip(), cleared at the start of each playPcm() call.
     */
    @Volatile
    private var skipRequested: Boolean = false

    /**
     * Pause flag — when true, playPcm() blocks between chunks until resumed.
     * Used for single-tap play/pause on Pixel Buds.
     */
    @Volatile
    var isPaused: Boolean = false
        private set

    /** True when audio is actively being written to AudioTrack. */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Monitor for pause/resume blocking. playPcm() waits here when paused. */
    private val pauseLock = Object()

    /**
     * Download WAV and queue for playback. Non-blocking.
     *
     * Download happens on a pool thread (parallel with other downloads).
     * Playback is queued on a single thread (serial, in arrival order).
     * Calls [onFailure] if download fails, so caller can trigger fallback TTS.
     */
    fun streamAsync(
        text: String,
        voice: String,
        speed: Float = 1.0f,
        onComplete: (() -> Unit)? = null,
        onFailure: () -> Unit
    ) {
        val submitTime = System.currentTimeMillis()
        Timber.i("AudioStreamClient: queued download, voice=%s, text=%d chars", voice, text.length)

        downloadExecutor.execute {
            val pcmData = downloadWav(text, voice, speed)

            if (pcmData != null) {
                val downloadMs = System.currentTimeMillis() - submitTime
                Timber.i("AudioStreamClient: WAV ready (%d bytes PCM, %dms), queuing playback",
                    pcmData.size, downloadMs)

                // Queue playback, then sentinel AFTER it on the same serial executor.
                // This guarantees the sentinel runs only after audio finishes.
                playbackExecutor.execute {
                    playPcm(pcmData)
                }
                if (onComplete != null) {
                    playbackExecutor.execute {
                        Timber.d("AudioStreamClient: playback sentinel reached, invoking onComplete")
                        onComplete()
                    }
                }
            } else {
                Timber.w("AudioStreamClient: download failed, queuing fallback on playback executor")
                // Queue fallback ON THE PLAYBACK EXECUTOR so it serializes with
                // any GPU audio already queued. onFailure must block until done
                // (use TtsEngine.speakBlocking, not speak).
                playbackExecutor.execute {
                    onFailure()
                }
                // Sentinel after fallback
                if (onComplete != null) {
                    playbackExecutor.execute {
                        Timber.d("AudioStreamClient: playback sentinel reached (after fallback), invoking onComplete")
                        onComplete()
                    }
                }
            }
        }
    }

    /**
     * Synchronous download + play. Used by UI diagnostic buttons.
     * Blocks until playback completes or fails.
     */
    fun streamAndPlay(text: String, voice: String, speed: Float = 1.0f): Boolean {
        val pcmData = downloadWav(text, voice, speed) ?: return false
        playPcm(pcmData)
        return true
    }

    /**
     * Replay a message via GPU TTS. Blocks until playback completes.
     * Formats the text as "{sender} says: {message}" and plays with
     * the original voice (defaulting to am_michael).
     *
     * Shared method used by:
     *   - UI replay buttons (MessagesScreen, HistoryScreen)
     *   - MediaSession callback (single-tap replay on Pixel Buds)
     *
     * Must be called on a background thread (does network I/O + AudioTrack).
     *
     * @return true if GPU TTS succeeded, false if download/playback failed
     */
    fun replayMessage(sender: String, message: String, voice: String?): Boolean {
        val resolvedVoice = if (voice.isNullOrBlank()) "am_michael" else voice
        val spokenText = "$sender says: $message"
        Timber.i("AudioStreamClient: replaying message from %s, voice=%s", sender, resolvedVoice)
        return streamAndPlay(spokenText, resolvedVoice)
    }

    /**
     * Download WAV from Oasis GPU, strip header, return raw PCM bytes.
     * Retries once after RETRY_DELAY_MS to handle Tailscale cold-start
     * (tunnel dormant after phone sleep, first request times out).
     * Returns null on failure.
     */
    private fun downloadWav(text: String, voice: String, speed: Float): ByteArray? {
        for (attempt in 1..MAX_RETRIES) {
            val result = attemptDownload(text, voice, speed, attempt)
            if (result != null) return result

            if (attempt < MAX_RETRIES) {
                Timber.i("AudioStreamClient: attempt %d failed, retrying in %dms (Tailscale cold-start?)",
                    attempt, RETRY_DELAY_MS)
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Timber.w("AudioStreamClient: retry sleep interrupted")
                    return null
                }
            }
        }
        return null
    }

    /**
     * Single download attempt. Returns raw PCM bytes or null on failure.
     */
    private fun attemptDownload(text: String, voice: String, speed: Float, attempt: Int): ByteArray? {
        val start = System.currentTimeMillis()

        try {
            val payload = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("speed", speed.toDouble())
            }

            val url = URL(TailscaleConfig.TTS_SERVER)
            Timber.i("AudioStreamClient: POST to %s [attempt %d], voice=%s, text=%d chars",
                TailscaleConfig.TTS_SERVER, attempt, voice, text.length)

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Content-Type", "application/json")
                // Disable keep-alive — socat proxy doesn't handle persistent
                // connections well, causing stale socket reuse on subsequent requests
                setRequestProperty("Connection", "close")
            }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val connectMs = System.currentTimeMillis() - start
            Timber.i("AudioStreamClient: connected in %dms [attempt %d], HTTP %d",
                connectMs, attempt, connection.responseCode)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("AudioStreamClient: HTTP %d from server [attempt %d]", connection.responseCode, attempt)
                connection.disconnect()
                return null
            }

            // Download entire WAV to memory
            val wavBytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    wavBytes.write(buffer, 0, n)
                }
            }
            connection.disconnect()

            val rawWav = wavBytes.toByteArray()
            val downloadMs = System.currentTimeMillis() - start
            Timber.i("AudioStreamClient: downloaded %d bytes in %dms [attempt %d]", rawWav.size, downloadMs, attempt)

            if (rawWav.size <= WAV_HEADER_SIZE) {
                Timber.w("AudioStreamClient: WAV too small (%d bytes) [attempt %d]", rawWav.size, attempt)
                return null
            }

            // Strip 44-byte WAV header, return raw PCM
            return rawWav.copyOfRange(WAV_HEADER_SIZE, rawWav.size)

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Timber.w(e, "AudioStreamClient: download FAILED after %dms [attempt %d]: %s",
                elapsed, attempt, e.message)
            return null
        }
    }

    /**
     * Play raw PCM bytes via AudioTrack. Blocks until done or skipped.
     * Writes in chunks so skip() can interrupt mid-playback.
     * Holds wakelock to prevent process death mid-playback.
     */
    private fun playPcm(pcmData: ByteArray) {
        skipRequested = false
        isPlaying = true

        val wakeLock = try {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Dispatch::AudioPlayback"
            ).apply { acquire(WAKELOCK_TIMEOUT) }
        } catch (e: SecurityException) {
            Timber.w(e, "AudioStreamClient: could not acquire WAKE_LOCK — continuing without it")
            null
        }

        try {
            val audioDurationMs = pcmData.size.toLong() * 1000 / (SAMPLE_RATE * 2)
            Timber.i("AudioStreamClient: playing %d bytes (%.1fs audio)",
                pcmData.size, audioDurationMs / 1000f)

            val audioTrack = createAudioTrack()
            currentAudioTrack = audioTrack
            audioTrack.play()

            // Write in chunks so skip() and pause() can interrupt between writes
            var offset = 0
            while (offset < pcmData.size && !skipRequested) {
                // Block here if paused — wait until resumed or skipped
                synchronized(pauseLock) {
                    while (isPaused && !skipRequested) {
                        try {
                            pauseLock.wait()
                        } catch (_: InterruptedException) {
                            Timber.d("AudioStreamClient: pause wait interrupted")
                        }
                    }
                }
                if (skipRequested) break

                val remaining = pcmData.size - offset
                val chunkSize = minOf(remaining, PLAYBACK_CHUNK_SIZE)
                val written = audioTrack.write(pcmData, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Timber.w("AudioStreamClient: AudioTrack.write returned %d, stopping", written)
                    break
                }
                offset += written
            }

            if (skipRequested) {
                Timber.i("AudioStreamClient: playback SKIPPED at offset %d/%d", offset, pcmData.size)
            } else {
                Timber.i("AudioStreamClient: playback complete")
            }

            audioTrack.stop()
            audioTrack.flush()
            currentAudioTrack = null
            audioTrack.release()
        } catch (e: Exception) {
            currentAudioTrack = null
            Timber.w(e, "AudioStreamClient: playback FAILED: %s", e.message)
        } finally {
            isPlaying = false
            isPaused = false
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    Timber.d("AudioStreamClient: wakelock released")
                }
            } catch (e: Exception) {
                Timber.w("AudioStreamClient: error releasing wakelock: %s", e.message)
            }
        }
    }

    /**
     * Cancel current audio playback. Called from MediaSession callback (earbud double-tap).
     * Sets skip flag so the playback chunk loop exits early, then stops the AudioTrack
     * to interrupt any blocking write.
     *
     * Safe to call from any thread. If nothing is playing, this is a no-op.
     */
    fun skip() {
        skipRequested = true
        // Wake up pause wait if paused, so skip can proceed
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        try {
            currentAudioTrack?.let { track ->
                Timber.i("AudioStreamClient: skip() — stopping current AudioTrack")
                track.pause()
                track.flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioStreamClient: skip() error (non-fatal)")
        }
    }

    /**
     * Pause current audio playback. AudioTrack pauses immediately,
     * playPcm() chunk loop blocks on pauseLock until resumed.
     * Safe to call from any thread. No-op if not playing.
     */
    fun pausePlayback() {
        if (!isPlaying || isPaused) return
        isPaused = true
        try {
            currentAudioTrack?.pause()
            Timber.i("AudioStreamClient: paused")
        } catch (e: Exception) {
            Timber.w(e, "AudioStreamClient: pausePlayback() error (non-fatal)")
        }
    }

    /**
     * Resume paused audio playback. AudioTrack resumes immediately,
     * playPcm() chunk loop wakes up and continues writing.
     * Safe to call from any thread. No-op if not paused.
     */
    fun resumePlayback() {
        if (!isPaused) return
        isPaused = false
        try {
            currentAudioTrack?.play()
            Timber.i("AudioStreamClient: resumed")
        } catch (e: Exception) {
            Timber.w(e, "AudioStreamClient: resumePlayback() error (non-fatal)")
        }
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /**
     * Lightweight connectivity test. Returns diagnostic string.
     * Runs on calling thread — wrap in coroutine/executor.
     */
    fun testConnection(): String {
        val start = System.currentTimeMillis()
        return try {
            val payload = JSONObject().apply {
                put("text", "test")
                put("voice", "am_michael")
                put("speed", 1.0)
            }

            val url = URL(TailscaleConfig.TTS_SERVER)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
            }

            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val connectMs = System.currentTimeMillis() - start
            val code = connection.responseCode
            val contentType = connection.contentType ?: "unknown"

            if (code == HttpURLConnection.HTTP_OK) {
                val buffer = ByteArray(64)
                val bytesRead = connection.inputStream.read(buffer)
                connection.disconnect()
                val totalMs = System.currentTimeMillis() - start
                val isWav = bytesRead >= 4 &&
                        buffer[0] == 'R'.code.toByte() &&
                        buffer[1] == 'I'.code.toByte() &&
                        buffer[2] == 'F'.code.toByte() &&
                        buffer[3] == 'F'.code.toByte()
                "OK: HTTP $code, type=$contentType, WAV=$isWav, ${connectMs}ms connect, ${totalMs}ms total"
            } else {
                connection.disconnect()
                "FAIL: HTTP $code in ${connectMs}ms"
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            "ERROR: ${e.javaClass.simpleName}: ${e.message} (${elapsed}ms)"
        }
    }

    fun shutdown() {
        downloadExecutor.shutdownNow()
        playbackExecutor.shutdownNow()
    }

    companion object {
        private const val SAMPLE_RATE = 24000       // Kokoro v1.0 output rate
        private const val CHUNK_SIZE = 8192          // Download buffer size
        private const val CONNECT_TIMEOUT = 5_000    // 5s connect timeout
        private const val READ_TIMEOUT = 30_000      // 30s read timeout
        private const val WAV_HEADER_SIZE = 44       // Standard WAV header size
        private const val WAKELOCK_TIMEOUT = 120_000L // 2 min max (safety net)
        private const val MAX_RETRIES = 2            // 1 retry after initial failure
        private const val RETRY_DELAY_MS = 2_000L    // 2s pause for Tailscale tunnel warmup
        private const val PLAYBACK_CHUNK_SIZE = 4800 // ~100ms of audio at 24kHz 16-bit mono (skip responsiveness)
    }
}
