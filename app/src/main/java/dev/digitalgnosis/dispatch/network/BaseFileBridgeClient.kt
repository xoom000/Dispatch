package dev.digitalgnosis.dispatch.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Base network client for communicating with the DG File Bridge on pop-os.
 *
 * Provides core OkHttp implementation, URL building, and common error handling.
 * All domain-specific repositories delegate their network calls here.
 */
@Singleton
class BaseFileBridgeClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val client = OkHttpClient.Builder()
        .addInterceptor(ChuckerInterceptor(context))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Build a full URL for a File Bridge endpoint.
     */
    fun buildUrl(path: String): String {
        val cleanPath = path.removePrefix("/")
        return "${TailscaleConfig.FILE_BRIDGE_SERVER}/$cleanPath"
    }

    /**
     * Execute a synchronous GET request and return the response body as string.
     * Call from a background thread.
     */
    fun get(path: String): String? {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return execute(request)
    }

    /**
     * Execute a synchronous POST request with JSON body.
     * Call from a background thread.
     */
    fun post(path: String, json: String): String? {
        val url = buildUrl(path)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return execute(request)
    }

    /**
     * Execute a synchronous PUT request with JSON body.
     */
    fun put(path: String, json: String): String? {
        val url = buildUrl(path)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .put(body)
            .build()

        return execute(request)
    }

    private fun execute(request: Request): String? {
        Timber.i("Network: %s %s", request.method, request.url)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Network: %s failed with code %d", request.url, response.code)
                    return null
                }
                val body = response.body?.string()
                Timber.d("Network: %s OK (%d bytes)", request.url, body?.length ?: 0)
                body
            }
        } catch (e: Exception) {
            Timber.e(e, "Network: %s error", request.url)
            null
        }
    }

    /**
     * Access to the raw OkHttpClient for specialized needs (like large file streams).
     */
    fun getRawClient(): OkHttpClient = client
}
