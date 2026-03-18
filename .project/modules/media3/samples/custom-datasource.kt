// SOURCE: https://github.com/google/ExoPlayer/issues/7566 (Java original, converted to Kotlin pattern)
// SOURCE: https://github.com/androidx/media/blob/release/libraries/datasource_okhttp/src/main/java/androidx/media3/datasource/okhttp/OkHttpDataSource.java
// Transfer listener lifecycle verified from OkHttpDataSource: transferInitializing -> transferStarted -> bytesTransferred (per read) -> transferEnded

package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A custom DataSource that wraps an InputStream.
 * Pattern verified from ExoPlayer issue #7566 and OkHttpDataSource source.
 *
 * CONTRACT (from OkHttpDataSource observation):
 *   open()  -- call transferInitializing(dataSpec), then transferStarted(dataSpec), return bytes available or C.LENGTH_UNSET
 *   read()  -- call bytesTransferred(bytesRead) after each successful read; return C.RESULT_END_OF_INPUT (-1) not 0 when exhausted
 *   close() -- call transferEnded(), then clean up
 */
class InputStreamDataSource(
    private val inputStreamProvider: () -> InputStream,
    isNetwork: Boolean = false
) : BaseDataSource(isNetwork) {

    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0L
    private var uri: Uri? = null
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)     // REQUIRED: before I/O

        inputStream = inputStreamProvider()

        // Skip to position if needed
        val skipped = inputStream!!.skip(dataSpec.position)
        if (skipped < dataSpec.position) {
            throw EOFException("Could not skip to position ${dataSpec.position}")
        }

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            dataSpec.length
        }

        opened = true
        transferStarted(dataSpec)          // REQUIRED: after successful open
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        val bytesRead = stream.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }

        bytesTransferred(bytesRead)        // REQUIRED: after every read
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()            // REQUIRED: in close if opened
            }
        }
    }

    class Factory(private val streamProvider: () -> InputStream) : DataSource.Factory {
        override fun createDataSource(): DataSource = InputStreamDataSource(streamProvider)
    }
}
