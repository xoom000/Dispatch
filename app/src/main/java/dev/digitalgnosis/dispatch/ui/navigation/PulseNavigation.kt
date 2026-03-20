package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.PulseScreen
import kotlinx.serialization.Serializable

/** Route for the Pulse tab — no args needed. */
@Serializable
data object PulseRoute

fun NavGraphBuilder.pulseDestination(
    refreshSignal: Int,
) {
    composable<PulseRoute> {
        PulseScreen(refreshSignal = refreshSignal)
    }
}

fun NavController.navigateToPulse() {
    navigate(PulseRoute) {
        launchSingleTop = true
    }
}
