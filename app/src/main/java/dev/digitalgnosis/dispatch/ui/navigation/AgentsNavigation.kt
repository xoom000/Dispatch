package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.AgentsScreen
import kotlinx.serialization.Serializable

/** Route for the agents/activity screen — no args needed. */
@Serializable
data object AgentsRoute

fun NavGraphBuilder.agentsDestination(
    eventRefreshSignal: Int = 0,
) {
    composable<AgentsRoute> {
        AgentsScreen(eventRefreshSignal = eventRefreshSignal)
    }
}

fun NavController.navigateToAgents() {
    navigate(AgentsRoute)
}
