package dev.digitalgnosis.dispatch.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tokenFlow = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getString(KEY_FCM_TOKEN, null)
    )
    val tokenFlow: kotlinx.coroutines.flow.StateFlow<String?> = _tokenFlow

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        _tokenFlow.value = token
        writeTokenFile(token)
        Timber.d("FCM token saved: %s", token)
    }

    fun getToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)

    private fun writeTokenFile(token: String) {
        try {
            File(context.filesDir, TOKEN_FILE).writeText(token)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write token file")
        }
    }

    companion object {
        private const val PREFS_NAME = "dispatch_prefs"
        private const val KEY_FCM_TOKEN = "fcm_device_token"
        private const val TOKEN_FILE = "fcm-token.txt"
    }
}
