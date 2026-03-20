package dev.digitalgnosis.dispatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.network.SseConnectionService
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.rootnav.RootNavScreen
import dev.digitalgnosis.dispatch.ui.theme.DispatchTheme
import dev.digitalgnosis.dispatch.ui.theme.DisplayDimensions
import dev.digitalgnosis.dispatch.ui.theme.LocalDisplayDimensions
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var ttsEngine: TtsEngine
    @Inject lateinit var modelManager: ModelManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // GAP-S2: Prevent screenshots, screen recording, and Recent Apps thumbnails
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // GAP-S3: Prevent tap-jacking via malicious overlay apps
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        requestNotificationPermission()

        // Start SSE service from Activity (foreground context) — safe on targetSdk 35+.
        SseConnectionService.start(this)

        setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current.density
            val displayDimensions = remember(configuration, density) {
                DisplayDimensions.fromConfiguration(configuration, density)
            }

            CompositionLocalProvider(
                LocalDisplayDimensions provides displayDimensions
            ) {
                DispatchTheme {
                    RootNavScreen(
                        tokenManager = tokenManager,
                        modelManager = modelManager,
                        ttsEngine = ttsEngine,
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
