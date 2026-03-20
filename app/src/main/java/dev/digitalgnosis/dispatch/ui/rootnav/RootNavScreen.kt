package dev.digitalgnosis.dispatch.ui.rootnav

import android.content.Intent
import android.net.Uri
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.digitalgnosis.dispatch.config.TailscaleConfig
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.network.SseConnectionService
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.navigation.*
import dev.digitalgnosis.dispatch.update.UpdateBanner
import dev.digitalgnosis.dispatch.update.UpdateStateManager

/**
 * Root navigation host for Dispatch.
 *
 * Replaces the old DispatchApp composable. This is the single entry point —
 * one NavHost, one navigation system. No more dual-system conflicts.
 *
 * Pattern: Bitwarden MainActivity + RootNavScreen (adapted for simpler auth model)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootNavScreen(
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    viewModel: RootNavViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsState()
    val navController = rememberNavController()
    val context = LocalContext.current

    // SSE refresh signals — passed to tab destinations
    val eventRefreshSignal: Int by SseConnectionService.eventFeedRefresh.collectAsState()
    val whiteboardRefresh: Int by SseConnectionService.whiteboardRefresh.collectAsState()

    // Self-update system
    val updateManager = viewModel<UpdateStateManager>(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return UpdateStateManager(context.applicationContext) as T
            }
        }
    )

    // Track which tab is selected based on current nav destination
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val selectedTab = when {
        currentRoute?.contains("ChatRoute") == true -> DispatchTab.CHAT
        currentRoute?.contains("PulseRoute") == true -> DispatchTab.PULSE
        currentRoute?.contains("BoardRoute") == true -> DispatchTab.BOARD
        currentRoute?.contains("GeminiRoute") == true -> DispatchTab.GEMINI
        else -> DispatchTab.CHAT
    }

    // Adaptive layout
    val configuration = LocalConfiguration.current
    val useNavigationRail = configuration.screenWidthDp >= 600

    // Determine if we're on a "push" screen (not a tab screen)
    val isOnPushScreen = currentRoute != null && !listOf(
        "ChatRoute", "PulseRoute", "BoardRoute", "GeminiRoute"
    ).any { currentRoute.contains(it) }

    val topBar: @Composable () -> Unit = {
        if (!isOnPushScreen) {
            TopAppBar(
                title = { Text("Dispatch") },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TailscaleConfig.SANDBOX_URL))
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.AccountTree, contentDescription = "Sandbox")
                    }
                    IconButton(onClick = { navController.navigateToSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { navController.navigateToLogViewer() }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Logs")
                    }
                }
            )
        }
    }

    val onTabSelected: (DispatchTab) -> Unit = { tab ->
        val route: Any = when (tab) {
            DispatchTab.CHAT -> ChatRoute
            DispatchTab.PULSE -> PulseRoute
            DispatchTab.BOARD -> BoardRoute
            DispatchTab.GEMINI -> GeminiRoute
        }
        navController.navigate(route) {
            // Pop up to the start destination to avoid building a huge back stack
            popUpTo(ChatRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Main scaffold
    if (useNavigationRail) {
        Scaffold(topBar = topBar) { padding ->
            Row(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (!isOnPushScreen) {
                    DispatchNavigationRail(
                        currentTab = selectedTab,
                        onTabSelected = onTabSelected,
                    )
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    UpdateBanner(updateManager)
                    NavHostContent(
                        navController = navController,
                        tokenManager = tokenManager,
                        modelManager = modelManager,
                        ttsEngine = ttsEngine,
                        eventRefreshSignal = eventRefreshSignal,
                        whiteboardRefresh = whiteboardRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        Scaffold(
            topBar = topBar,
            bottomBar = {
                if (!isOnPushScreen) {
                    BottomNavBar(
                        currentTab = selectedTab,
                        onTabSelected = onTabSelected,
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                UpdateBanner(updateManager)
                NavHostContent(
                    navController = navController,
                    tokenManager = tokenManager,
                    modelManager = modelManager,
                    ttsEngine = ttsEngine,
                    eventRefreshSignal = eventRefreshSignal,
                    whiteboardRefresh = whiteboardRefresh,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // Splash overlay during initial startup
    if (state is RootNavState.Splash) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun NavHostContent(
    navController: androidx.navigation.NavHostController,
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    eventRefreshSignal: Int,
    whiteboardRefresh: Int,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ChatRoute,
        modifier = modifier,
    ) {
        // ── Tab destinations ─────────────────────────────────────────────
        chatDestination(
            onComposeNew = { navController.navigateToSend() },
            onOpenConversation = { sid, dept ->
                navController.navigateToMessages(threadId = sid, department = dept)
            },
        )

        pulseDestination(refreshSignal = eventRefreshSignal)

        boardDestination(
            whiteboardRefresh = whiteboardRefresh,
            onNavigateToThread = { navController.navigate(ChatRoute) },
        )

        geminiDestination()

        // ── Push destinations ────────────────────────────────────────────
        messagesDestination(onBack = { navController.navigateUp() })

        liveSessionDestination(onBack = { navController.navigateUp() })

        sendDestination(onDismiss = { navController.navigateUp() })

        settingsDestination(
            tokenManager = tokenManager,
            modelManager = modelManager,
            ttsEngine = ttsEngine,
            onBack = { navController.navigateUp() },
        )

        logViewerDestination(onBack = { navController.navigateUp() })
    }
}
