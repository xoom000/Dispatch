package dev.digitalgnosis.dispatch.network

import dev.digitalgnosis.dispatch.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * OkHttp interceptor that adds the File Bridge API key to every request.
 *
 * The key is loaded from BuildConfig.FB_API_KEY (sourced from local.properties).
 * If the key is empty, requests proceed without auth (will get 401/503 from server).
 *
 * Apply this to EVERY OkHttpClient that talks to File Bridge:
 *   - BaseFileBridgeClient (main + streaming)
 *   - SseConnectionService (SSE event stream)
 *   - AnthropicAuthManager (auth token fetch)
 */
class FileBridgeAuthInterceptor : Interceptor {

    private val apiKey: String = BuildConfig.FB_API_KEY

    init {
        if (apiKey.isBlank()) {
            Timber.e("FileBridgeAuth: FB_API_KEY is empty — File Bridge requests will fail")
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        if (apiKey.isBlank()) {
            return chain.proceed(original)
        }

        val authed = original.newBuilder()
            .header("X-API-Key", apiKey)
            .build()

        return chain.proceed(authed)
    }
}
