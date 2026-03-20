package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.LogViewerScreen
import kotlinx.serialization.Serializable

/** Route for the log viewer debug screen — no args needed. */
@Serializable
data object LogViewerRoute

fun NavGraphBuilder.logViewerDestination(
    onBack: () -> Unit,
) {
    composable<LogViewerRoute> {
        LogViewerScreen(onBack = onBack)
    }
}

fun NavController.navigateToLogViewer() {
    navigate(LogViewerRoute)
}
