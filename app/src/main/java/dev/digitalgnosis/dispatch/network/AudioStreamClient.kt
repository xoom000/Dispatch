package dev.digitalgnosis.dispatch.network

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS download + playback client for on-demand replay and diagnostics.
 *
 * Used by:
 *   - UI replay buttons (MessagesScreen, HistoryScreen, GeminiViewModel)
 *   - Settings screen diagnostic buttons (streamAndPlay, testConnection)
 *
 * NOT used for FCM message playback — that path goes through
 * DispatchPlaybackService (Media3 ExoPlayer + MediaSessionService).
 *
 * This client still uses AudioTrack for playback (legacy). A future
 * migration will route replay through DispatchPlaybackService as well.
 *
 * WAV format: 16-bit PCM, 24kHz, mono.
 */
@Singleton
class AudioStreamClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

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
     * Used by UI replay buttons in MessagesScreen, HistoryScreen, GeminiViewModel.
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
     * Retries once after RETRY_DELAY_MS to handle Tailscale cold-start.
     * Returns null on failure.
     */
    private fun downloadWav(text: String, voice: String, speed: Float): ByteArray? {
        for (attempt in 1..MAX_RETRIES) {
            val result = attemptDownload(text, voice, speed, attempt)
            if (result != null) return result

            if (attempt < MAX_RETRIES) {
                Timber.i("AudioStreamClient: attempt %d failed, retrying in %dms",
                    attempt, RETRY_DELAY_MS)
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

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
                Timber.w("AudioStreamClient: HTTP %d [attempt %d]", connection.responseCode, attempt)
                connection.disconnect()
                return null
            }

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
            Timber.i("AudioStreamClient: downloaded %d bytes in %dms [attempt %d]",
                rawWav.size, downloadMs, attempt)

            if (rawWav.size <= WAV_HEADER_SIZE) {
                Timber.w("AudioStreamClient: WAV too small (%d bytes) [attempt %d]",
                    rawWav.size, attempt)
                return null
            }

            return rawWav.copyOfRange(WAV_HEADER_SIZE, rawWav.size)

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Timber.w(e, "AudioStreamClient: download FAILED after %dms [attempt %d]",
                elapsed, attempt)
            return null
        }
    }

    /**
     * Play raw PCM bytes via AudioTrack. Blocks until complete.
     * Used for synchronous replay from UI buttons.
     */
    private fun playPcm(pcmData: ByteArray) {
        try {
            val audioDurationMs = pcmData.size.toLong() * 1000 / (SAMPLE_RATE * 2)
            Timber.i("AudioStreamClient: playing %d bytes (%.1fs audio)",
                pcmData.size, audioDurationMs / 1000f)

            val audioTrack = createAudioTrack()
            audioTrack.play()

            audioTrack.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)

            // Wait for drain before stopping
            val totalFrames = pcmData.size / 2
            waitForDrain(audioTrack, totalFrames)

            audioTrack.stop()
            audioTrack.flush()
            audioTrack.release()
            Timber.i("AudioStreamClient: playback complete")
        } catch (e: Exception) {
            Timber.w(e, "AudioStreamClient: playback FAILED: %s", e.message)
        }
    }

    private fun waitForDrain(audioTrack: AudioTrack, totalFrames: Int) {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (audioTrack.playbackHeadPosition >= totalFrames) return
            try { Thread.sleep(DRAIN_POLL_MS) } catch (_: InterruptedException) { }
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

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHUNK_SIZE = 8192
        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 30_000
        private const val WAV_HEADER_SIZE = 44
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2_000L
        private const val DRAIN_TIMEOUT_MS = 3_000L
        private const val DRAIN_POLL_MS = 50L
    }
}
