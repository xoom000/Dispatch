package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.HistoryScreen
import kotlinx.serialization.Serializable

/** Route for the message history screen — no args needed. */
@Serializable
data object HistoryRoute

fun NavGraphBuilder.historyDestination() {
    composable<HistoryRoute> {
        HistoryScreen()
    }
}

fun NavController.navigateToHistory() {
    navigate(HistoryRoute)
}
