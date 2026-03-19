package dev.digitalgnosis.dispatch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dagger.hilt.android.AndroidEntryPoint
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.network.AudioStreamClient
import dev.digitalgnosis.dispatch.network.SseConnectionService
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.navigation.BottomNavBar
import dev.digitalgnosis.dispatch.ui.navigation.DispatchTab
import dev.digitalgnosis.dispatch.ui.screens.*
import dev.digitalgnosis.dispatch.ui.theme.DispatchTheme
import dev.digitalgnosis.dispatch.ui.theme.DisplayDimensions
import dev.digitalgnosis.dispatch.ui.theme.LocalDisplayDimensions
import dev.digitalgnosis.dispatch.update.UpdateBanner
import dev.digitalgnosis.dispatch.update.UpdateStateManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var ttsEngine: TtsEngine
    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var audioStreamClient: AudioStreamClient

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        // Start SSE service from Activity (foreground context) — safe on targetSdk 35+.
        // NOT started from Application.onCreate() which can run from background contexts
        // where foreground service starts throw ForegroundServiceStartNotAllowedException.
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
                    DispatchApp(
                        tokenManager = tokenManager,
                        modelManager = modelManager,
                        ttsEngine = ttsEngine,
                        audioStreamClient = audioStreamClient,
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

/** Hoisted draft state for the Send tab. */
class SendDraft {
    var messageText: String = ""
    var selectedDepts: Set<String> = emptySet()
    var invokeAgent: Boolean = true
    var selectedThreadId: String? = null
    data class DraftFile(val name: String, val bytes: ByteArray)
    var attachedFiles: List<DraftFile> = emptyList()
    fun clearDraft() {
        messageText = ""
        attachedFiles = emptyList()
        selectedThreadId = null
    }
}

private data class LiveSessionParams(
    val department: String,
    val invokedAt: Long,
    val sessionId: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchApp(
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    audioStreamClient: AudioStreamClient,
) {
    val context = LocalContext.current
    val dims = LocalDisplayDimensions.current
    val eventRefreshSignal by SseConnectionService.eventFeedRefresh.collectAsState()
    val whiteboardRefresh by SseConnectionService.whiteboardRefresh.collectAsState()
    var showLogViewer by rememberSaveable { mutableStateOf(false) }

    // Self-update system — checks GitHub releases on launch
    val updateManager = viewModel<UpdateStateManager>(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return UpdateStateManager(context.applicationContext) as T
            }
        }
    )

    val sendDraft = remember { SendDraft() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCompose by rememberSaveable { mutableStateOf(false) }
    var liveSessionParams by remember { mutableStateOf<LiveSessionParams?>(null) }
    var currentTab by rememberSaveable { mutableStateOf(DispatchTab.CHAT.name) }
    val selectedTab = try { DispatchTab.valueOf(currentTab) } catch (e: Exception) { DispatchTab.CHAT }

    if (showLogViewer) {
        LogViewerScreen(onBack = { showLogViewer = false })
        return
    }

    if (showSettings) {
        SettingsScreen(
            modifier = Modifier,
            tokenManager = tokenManager,
            modelManager = modelManager,
            ttsEngine = ttsEngine,
            audioStreamClient = audioStreamClient,
            onBack = { showSettings = false },
        )
        return
    }

    if (liveSessionParams != null) {
        LiveSessionScreen(
            department = liveSessionParams!!.department,
            invokedAt = liveSessionParams!!.invokedAt,
            sessionId = liveSessionParams!!.sessionId,
            onBack = { liveSessionParams = null },
        )
        return
    }

    if (showCompose) {
        SendScreen(
            draft = sendDraft,
            onDismiss = { showCompose = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dispatch") },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TailscaleConfig.SANDBOX_URL))
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.AccountTree, contentDescription = "Sandbox")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showLogViewer = true }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Logs")
                    }
                }
            )
        },
        bottomBar = {
            BottomNavBar(
                currentTab = selectedTab,
                onTabSelected = { currentTab = it.name },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Self-update banner — shown when a newer version is on GitHub
            UpdateBanner(updateManager)

            val screenModifier = Modifier.fillMaxSize()

            when (selectedTab) {
                DispatchTab.CHAT -> ChatScreen(
                    modifier = screenModifier,
                    onComposeNew = { showCompose = true },
                )
                DispatchTab.PULSE -> PulseScreen(
                    modifier = screenModifier,
                    refreshSignal = eventRefreshSignal,
                )
                DispatchTab.BOARD -> BoardScreen(
                    modifier = screenModifier,
                    whiteboardRefresh = whiteboardRefresh,
                    onNavigateToThread = { currentTab = DispatchTab.CHAT.name },
                )
                DispatchTab.GEMINI -> GeminiWorkspaceScreen(
                    modifier = screenModifier,
                )
            }
        }
    }
}
