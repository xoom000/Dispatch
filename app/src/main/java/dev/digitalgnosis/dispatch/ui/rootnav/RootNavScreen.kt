package dev.digitalgnosis.dispatch.ui.rootnav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.navigation.ChatRoute
import dev.digitalgnosis.dispatch.ui.navigation.boardDestination
import dev.digitalgnosis.dispatch.ui.navigation.chatDestination
import dev.digitalgnosis.dispatch.ui.navigation.geminiDestination
import dev.digitalgnosis.dispatch.ui.navigation.liveSessionDestination
import dev.digitalgnosis.dispatch.ui.navigation.logViewerDestination
import dev.digitalgnosis.dispatch.ui.navigation.messagesDestination
import dev.digitalgnosis.dispatch.ui.navigation.navigateToLiveSession
import dev.digitalgnosis.dispatch.ui.navigation.navigateToLogViewer
import dev.digitalgnosis.dispatch.ui.navigation.navigateToMessages
import dev.digitalgnosis.dispatch.ui.navigation.navigateToSend
import dev.digitalgnosis.dispatch.ui.navigation.navigateToSettings
import dev.digitalgnosis.dispatch.ui.navigation.pulseDestination
import dev.digitalgnosis.dispatch.ui.navigation.sendDestination
import dev.digitalgnosis.dispatch.ui.navigation.settingsDestination
/**
 * Root navigation host for Dispatch.
 *
 * Observes [RootNavViewModel.stateFlow] and drives the NavHost accordingly.
 * When state is [RootNavState.Splash] the splash screen shows; once it
 * transitions to [RootNavState.Main] the full tabbed scaffold is shown.
 *
 * This composable owns the [NavController] and connects all destinations
 * via the typed-route extension functions from the *Navigation.kt files.
 *
 * Pattern: Bitwarden RootNavScreen.kt
 */
@Composable
fun RootNavScreen(
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    viewModel: RootNavViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsState()
    val navController = rememberNavController()

    // Observe SSE refresh signals — passed down to tab destinations.
    val eventRefreshSignal by dev.digitalgnosis.dispatch.network.SseConnectionService
        .eventFeedRefresh.collectAsState()
    val whiteboardRefresh by dev.digitalgnosis.dispatch.network.SseConnectionService
        .whiteboardRefresh.collectAsState()

    // Navigate from Splash → Main when state changes.
    LaunchedEffect(state) {
        if (state is RootNavState.Main) {
            navController.navigate(ChatRoute) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ChatRoute,
    ) {
        // ── Splash ──────────────────────────────────────────────────────────
        // Shown only during initial startup before MainScaffold is ready.
        // We don't define a dedicated SplashRoute — the host starts at Chat
        // because the Splash is a simple overlay.

        // ── Tab destinations (nested via MainScaffold) ──────────────────────
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

        // ── Push destinations ───────────────────────────────────────────────
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

    // Splash overlay — covers NavHost during initial startup.
    if (state is RootNavState.Splash) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}
