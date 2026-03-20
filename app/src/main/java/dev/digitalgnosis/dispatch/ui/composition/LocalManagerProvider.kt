package dev.digitalgnosis.dispatch.ui.composition

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

// ── Permissions ───────────────────────────────────────────────────────────────

/**
 * Interface for requesting runtime permissions from Compose.
 *
 * Activity-bound: cannot be injected into ViewModels.
 * Provided via [LocalPermissionsManager] CompositionLocal.
 */
interface PermissionsManager {
    fun requestPermission(permission: String, onResult: (Boolean) -> Unit)
    fun requestMultiplePermissions(
        permissions: Array<String>,
        onResult: (Map<String, Boolean>) -> Unit,
    )
}

/** CompositionLocal for [PermissionsManager]. Crashes clearly if not provided. */
val LocalPermissionsManager = staticCompositionLocalOf<PermissionsManager> {
    error("LocalPermissionsManager not provided — wrap with LocalManagerProvider { }")
}

// ── Intents ───────────────────────────────────────────────────────────────────

/**
 * Interface for launching external intents from Compose.
 *
 * Activity-bound: cannot be injected into ViewModels.
 * Provided via [LocalIntentManager] CompositionLocal.
 */
interface IntentManager {
    fun launchUrl(url: String)
    fun launchIntent(intent: Intent)
    fun shareText(text: String, title: String? = null)
}

/** CompositionLocal for [IntentManager]. Crashes clearly if not provided. */
val LocalIntentManager = staticCompositionLocalOf<IntentManager> {
    error("LocalIntentManager not provided — wrap with LocalManagerProvider { }")
}

// ── Exit ──────────────────────────────────────────────────────────────────────

/**
 * Interface for exiting the app from Compose.
 *
 * Activity-bound: cannot be injected into ViewModels.
 * Provided via [LocalExitManager] CompositionLocal.
 */
interface ExitManager {
    fun exitApp()
}

/** CompositionLocal for [ExitManager]. Crashes clearly if not provided. */
val LocalExitManager = staticCompositionLocalOf<ExitManager> {
    error("LocalExitManager not provided — wrap with LocalManagerProvider { }")
}

// ── Provider composable ───────────────────────────────────────────────────────

/**
 * LocalManagerProvider — wraps the composition tree with all Activity-dependent managers.
 *
 * Pattern: Bitwarden LocalManagerProvider.kt
 *
 * Call this once in MainActivity.setContent { } below the theme, above the nav host.
 * Each manager is instantiated using the Activity context and provided to all descendants
 * via CompositionLocal so ViewModels stay pure (no Activity/Context dependencies).
 *
 * Usage:
 * ```kotlin
 * setContent {
 *     DispatchTheme {
 *         LocalManagerProvider {
 *             DispatchApp(...)
 *         }
 *     }
 * }
 * ```
 */
@Composable
fun LocalManagerProvider(
    context: Context,
    activity: Activity,
    content: @Composable () -> Unit,
) {
    val permissionsManager = rememberPermissionsManager()
    val intentManager = rememberIntentManager(context)
    val exitManager = rememberExitManager(activity)

    CompositionLocalProvider(
        LocalPermissionsManager provides permissionsManager,
        LocalIntentManager provides intentManager,
        LocalExitManager provides exitManager,
        content = content,
    )
}

// ── Default implementations ───────────────────────────────────────────────────

@Composable
private fun rememberPermissionsManager(): PermissionsManager {
    // Stub implementation — Activity-level launchers not yet wired.
    // Replace with a real implementation backed by rememberLauncherForActivityResult
    // once permission flows are needed.
    return object : PermissionsManager {
        override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
            // No-op stub — upgrade when permission flows are added
        }

        override fun requestMultiplePermissions(
            permissions: Array<String>,
            onResult: (Map<String, Boolean>) -> Unit,
        ) {
            // No-op stub — upgrade when permission flows are added
        }
    }
}

@Composable
private fun rememberIntentManager(context: Context): IntentManager {
    return object : IntentManager {
        override fun launchUrl(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        override fun launchIntent(intent: Intent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        override fun shareText(text: String, title: String?) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (title != null) putExtra(Intent.EXTRA_TITLE, title)
            }
            context.startActivity(Intent.createChooser(intent, title))
        }
    }
}

@Composable
private fun rememberExitManager(activity: Activity): ExitManager {
    return object : ExitManager {
        override fun exitApp() {
            activity.finishAffinity()
        }
    }
}
