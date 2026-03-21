package dev.digitalgnosis.dispatch.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom DataSource that POSTs to Kokoro's streaming TTS endpoint
 * and feeds audio bytes directly to ExoPlayer as they generate.
 *
 * ExoPlayer calls open() → we POST to Kokoro, get a chunked response.
 * ExoPlayer calls read() → we hand it bytes from the response stream.
 * ExoPlayer starts playback as soon as it has the WAV header + first PCM chunk.
 *
 * Implements the FULL BaseDataSource contract verified from OkHttpDataSource:
 *   open()  -> transferInitializing() -> transferStarted() -> return LENGTH_UNSET
 *   read()  -> bytesTransferred() after each read -> RESULT_END_OF_INPUT on EOF
 *   close() -> transferEnded()
 *
 * Reference: .project/modules/media3/samples/datasource-contract.md
 */
@OptIn(UnstableApi::class)
internal class TtsStreamDataSource(
    private val streamRegistry: ConcurrentHashMap<String, StreamRequest>,
    private val streamingClient: OkHttpClient,
) : BaseDataSource(/* isNetwork= */ true) {

    private var response: okhttp3.Response? = null
    private var inputStream: InputStream? = null
    private var dataSpec: DataSpec? = null
    private var traceId: String? = null
    private var totalBytesRead = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec

        val streamId = dataSpec.uri.lastPathSegment
            ?: throw IOException("Missing stream ID in URI: ${dataSpec.uri}")

        // Use get(), not remove(). ExoPlayer retries on error and calls open() again
        // with the same URI. Cleanup happens in close() instead.
        // If the stream ID isn't found, this is a stale load from a previous media source
        // that was cleared via clearMediaItems(). Log and return gracefully — ExoPlayer
        // treats the IOException as a source error and moves on.
        val req = streamRegistry[streamId]
        if (req == null) {
            Timber.w("TtsStreamDataSource: stale stream ID %s — likely from cleared media source", streamId)
            throw IOException("Stale stream ID: $streamId (previous media source was cleared)")
        }

        traceId = req.traceId

        // REQUIRED: signal transfer initializing BEFORE any I/O
        transferInitializing(dataSpec)

        val payload = JSONObject().apply {
            put("text", req.text)
            put("voice", req.voice)
            put("speed", req.speed.toDouble())
        }

        val requestBuilder = Request.Builder()
            .url(TailscaleConfig.TTS_STREAM_SERVER)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        req.traceId?.let { requestBuilder.header("X-Trace-Id", it) }

        Timber.i("[trace:%s] TtsStreamDataSource: POST to Kokoro", traceId ?: "none")

        response = streamingClient.newCall(requestBuilder.build()).execute()

        if (!response!!.isSuccessful) {
            val code = response!!.code
            response!!.close()
            throw IOException("Kokoro stream returned HTTP $code")
        }

        inputStream = response!!.body?.byteStream()
            ?: throw IOException("Kokoro stream returned empty body")

        // Strip the 44-byte WAV header. Kokoro sends placeholder data_size=0x7FFFFF00
        // which causes WavExtractor to never reach STATE_ENDED (it recalculates bytesLeft
        // from the header size every read call). By consuming the header here and using
        // RawPcmExtractor, ExoPlayer reads raw PCM and stops cleanly at END_OF_INPUT.
        val header = ByteArray(WAV_HEADER_BYTES)
        var headerRead = 0
        while (headerRead < WAV_HEADER_BYTES) {
            val n = inputStream!!.read(header, headerRead, WAV_HEADER_BYTES - headerRead)
            if (n == -1) throw IOException("Stream ended before WAV header complete")
            headerRead += n
        }

        opened = true

        // REQUIRED: signal transfer started AFTER successful open
        transferStarted(dataSpec)

        val connectMs = System.currentTimeMillis() - req.startTime
        Timber.i("[trace:%s] TtsStreamDataSource: connected, header stripped, %dms",
            traceId ?: "none", connectMs)

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT

        val bytesRead = try {
            stream.read(buffer, offset, length)
        } catch (e: IOException) {
            Timber.w("[trace:%s] TtsStreamDataSource: IOException after %d bytes: %s",
                traceId ?: "none", totalBytesRead, e.message)
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRead == -1) {
            Timber.i("[trace:%s] TtsStreamDataSource: END_OF_INPUT after %d bytes",
                traceId ?: "none", totalBytesRead)
            return C.RESULT_END_OF_INPUT
        }

        totalBytesRead += bytesRead

        // REQUIRED: notify transfer listeners after every successful read
        bytesTransferred(bytesRead)

        return bytesRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        Timber.i("[trace:%s] TtsStreamDataSource: close() — %d bytes total",
            traceId ?: "none", totalBytesRead)
        try { inputStream?.close() } catch (_: Exception) {}
        try { response?.close() } catch (_: Exception) {}
        inputStream = null
        response = null

        // Clean up the registry entry now that we're done (or failed).
        // Safe to call on retries — remove() is idempotent on ConcurrentHashMap.
        dataSpec?.uri?.lastPathSegment?.let { streamRegistry.remove(it) }

        if (opened) {
            opened = false
            // REQUIRED: signal transfer ended in close
            transferEnded()
        }
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44
    }
}
