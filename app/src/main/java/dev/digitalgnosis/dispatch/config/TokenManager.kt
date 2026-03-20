package dev.digitalgnosis.dispatch.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.digitalgnosis.dispatch.network.FileBridgeAuthInterceptor
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tokenFlow = MutableStateFlow(
        prefs.getString(KEY_FCM_TOKEN, null)
    )
    val tokenFlow: StateFlow<String?> = _tokenFlow

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        _tokenFlow.value = token
        writeTokenFile(token)
        registerWithServer(token)
        Timber.d("FCM token saved: %s...%s", token.take(8), token.takeLast(4))
    }

    fun getToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)

    /**
     * Register token on app startup — ensures the server always has
     * the current token even if onNewToken didn't fire.
     */
    fun ensureRegistered() {
        val token = getToken() ?: return
        registerWithServer(token)
    }

    private fun writeTokenFile(token: String) {
        try {
            File(context.filesDir, TOKEN_FILE).writeText(token)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write token file")
        }
    }

    /**
     * POST the FCM token to File Bridge so the dispatch CLI
     * knows where to push voice notifications.
     *
     * Fire-and-forget on IO dispatcher. Failures are logged but
     * don't crash — the token will be retried on next app start.
     */
    private fun registerWithServer(token: String) {
        scope.launch {
            try {
                val url = "${TailscaleConfig.FILE_BRIDGE_SERVER}/config/device-token"
                val json = """{"token":"$token"}"""
                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                // FileBridgeAuthInterceptor reads from BuildConfig directly —
                // no DI needed. Must include it or the endpoint returns 401.
                val client = OkHttpClient.Builder()
                    .addInterceptor(FileBridgeAuthInterceptor())
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .readTimeout(java.time.Duration.ofSeconds(10))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Timber.i("FCM token registered with File Bridge: %s", response.body?.string())
                } else {
                    Timber.w("FCM token registration failed: %d %s", response.code, response.message)
                }
                response.close()
            } catch (e: Exception) {
                Timber.w(e, "FCM token registration failed (will retry on next launch)")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "dispatch_prefs"
        private const val KEY_FCM_TOKEN = "fcm_device_token"
        private const val TOKEN_FILE = "fcm-token.txt"
    }
}
