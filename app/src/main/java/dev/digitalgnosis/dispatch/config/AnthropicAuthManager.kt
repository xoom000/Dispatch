package dev.digitalgnosis.dispatch.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Anthropic OAuth credentials for the Sessions API.
 *
 * Stores OAuth bearer token and org UUID in EncryptedSharedPreferences.
 * The bridge environment ID is discovered via GET /v1/environment_providers
 * or hardcoded for the known pop-os bridge.
 *
 * Auth flow (current): Manual token entry via Settings screen.
 * Auth flow (future): Browser OAuth redirect via claude.ai.
 */
@Singleton
class AnthropicAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "anthropic_auth",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Timber.e(e, "AnthropicAuth: EncryptedSharedPreferences failed, using fallback")
            context.getSharedPreferences("anthropic_auth_fallback", Context.MODE_PRIVATE)
        }
    }

    /** OAuth bearer token from claude.ai login. */
    var oauthToken: String?
        get() = prefs.getString(KEY_OAUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OAUTH_TOKEN, value).apply()

    /** Organization UUID — needed for all Sessions API calls. */
    var orgUuid: String?
        get() = prefs.getString(KEY_ORG_UUID, null)
        set(value) = prefs.edit().putString(KEY_ORG_UUID, value).apply()

    /** Bridge environment ID — the pop-os remote-control server. */
    var bridgeEnvironmentId: String?
        get() = prefs.getString(KEY_BRIDGE_ENV_ID, DEFAULT_BRIDGE_ENV_ID)
        set(value) = prefs.edit().putString(KEY_BRIDGE_ENV_ID, value).apply()

    /** Whether we have enough credentials to make API calls. */
    val isAuthenticated: Boolean
        get() = !oauthToken.isNullOrBlank()

    /** Whether we have all three pieces needed for session creation. */
    val isFullyConfigured: Boolean
        get() = isAuthenticated && !orgUuid.isNullOrBlank() && !bridgeEnvironmentId.isNullOrBlank()

    /** Build the auth headers required for all Sessions API calls. */
    fun buildHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "anthropic-version" to API_VERSION,
            "anthropic-beta" to BETA_HEADER,
        )
        oauthToken?.let { headers["Authorization"] = "Bearer $it" }
        orgUuid?.let { headers["x-organization-uuid"] = it }
        return headers
    }

    /** Clear all stored credentials. */
    fun logout() {
        prefs.edit()
            .remove(KEY_OAUTH_TOKEN)
            .remove(KEY_ORG_UUID)
            .apply()
        Timber.i("AnthropicAuth: credentials cleared")
    }

    companion object {
        private const val KEY_OAUTH_TOKEN = "oauth_token"
        private const val KEY_ORG_UUID = "org_uuid"
        private const val KEY_BRIDGE_ENV_ID = "bridge_env_id"

        /** Known pop-os bridge environment — from `claude remote-control` output. */
        private const val DEFAULT_BRIDGE_ENV_ID = "env_01DN9pPUuKQ8ZZmY5JXqf61y"

        /** Sessions API version header. */
        private const val API_VERSION = "2023-06-01"

        /** Required beta header for bridge/BYOC features. */
        private const val BETA_HEADER = "ccr-byoc-2025-07-29"

        /** Base URL for all Sessions API calls. */
        const val BASE_URL = "https://api.anthropic.com"
    }
}
