package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.BoardScreen
import kotlinx.serialization.Serializable

/** Route for the Board tab — no args needed. */
@Serializable
data object BoardRoute

fun NavGraphBuilder.boardDestination(
    whiteboardRefresh: Int,
    onNavigateToThread: ((String) -> Unit)? = null,
) {
    composable<BoardRoute> {
        BoardScreen(
            whiteboardRefresh = whiteboardRefresh,
            onNavigateToThread = onNavigateToThread,
        )
    }
}

fun NavController.navigateToBoard() {
    navigate(BoardRoute) {
        launchSingleTop = true
    }
}
