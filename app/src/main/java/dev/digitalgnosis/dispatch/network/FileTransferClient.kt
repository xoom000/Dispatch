package dev.digitalgnosis.dispatch.network

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles file transfers between the Dispatch app and the DG File Bridge.
 *
 * Specialized for streaming large downloads and handling multi-part uploads.
 * Uses OkHttp for robust connection pooling and cancellation.
 */
@Singleton
class FileTransferClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: BaseFileBridgeClient
) {

    // Specialized client for large file transfers (updates, attachments)
    // Extended timeouts to 5 minutes for large builds over Tailscale.
    private val downloadClient = networkClient.getRawClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Download a file from the File Bridge and stream directly to storage.
     */
    fun downloadToStorage(
        fileUrl: String,
        fileName: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): String? {
        val start = System.currentTimeMillis()
        Timber.i("FileTransfer: downloading %s from %s", fileName, fileUrl)

        var mediaUri: android.net.Uri? = null
        try {
            val request = Request.Builder().url(fileUrl).get().build()
            val response = downloadClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Timber.w("FileTransfer: HTTP %d from %s", response.code, fileUrl)
                return null
            }

            val body = response.body ?: return null
            val totalBytes = body.contentLength()

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dispatch")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            mediaUri = uri

            var downloaded = 0L
            val source = body.source()
            resolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, totalBytes)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Timber.i("FileTransfer: downloaded %d bytes in %dms", downloaded, System.currentTimeMillis() - start)
            return fileName

        } catch (e: Exception) {
            Timber.w(e, "FileTransfer: download FAILED")
            if (mediaUri != null) {
                try { context.contentResolver.delete(mediaUri, null, null) } catch (_: Exception) {}
            }
            return null
        }
    }

    /**
     * Upload a file to a department's cmail inbox.
     */
    fun uploadToDepartment(
        fileBytes: ByteArray,
        fileName: String,
        department: String,
        message: String = "",
        subject: String = "File from Nigel",
    ): Boolean {
        val start = System.currentTimeMillis()
        Timber.i("FileTransfer: uploading %s to %s", fileName, department)

        try {
            val mediaType = guessMimeType(fileName).toMediaType()
            val filePart = fileBytes.toRequestBody(mediaType)
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, filePart)
                .addFormDataPart("department", department)
                .addFormDataPart("sender", "nigel")
                .addFormDataPart("message", message)
                .addFormDataPart("subject", subject)
                .build()

            val request = Request.Builder()
                .url("${TailscaleConfig.FILE_BRIDGE_SERVER}/upload")
                .post(requestBody)
                .build()

            networkClient.getRawClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Timber.i("FileTransfer: upload OK in %dms", System.currentTimeMillis() - start)
                    return true
                }
                Timber.w("FileTransfer: upload failed with code %d", response.code)
                return false
            }
        } catch (e: Exception) {
            Timber.w(e, "FileTransfer: upload error")
            return false
        }
    }

    /**
     * Download a file directly to internal cache storage.
     * Useful for system tasks like app updates.
     */
    fun downloadToInternalFile(
        fileUrl: String,
        fileName: String
    ): java.io.File? {
        Timber.i("FileTransfer: internal download starting from %s", fileUrl)
        try {
            val request = Request.Builder().url(fileUrl).get().build()
            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("FileTransfer: internal download HTTP %d", response.code)
                return null
            }
            
            val body = response.body ?: run {
                Timber.w("FileTransfer: internal download body is NULL")
                return null
            }
            val file = java.io.File(context.cacheDir, fileName)
            Timber.d("FileTransfer: writing to %s", file.absolutePath)
            
            var totalRead = 0L
            body.source().use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                    }
                }
            }
            Timber.i("FileTransfer: internal download complete (%d bytes)", totalRead)
            return file
        } catch (e: Exception) {
            Timber.e(e, "FileTransfer: internal download FAILED: %s", e.message)
            return null
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "zip" -> "application/zip"
            "html" -> "text/html"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }
}
