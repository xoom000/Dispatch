package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.SendScreen
import kotlinx.serialization.Serializable

/** Route for the compose/send screen — no persistent args (draft is in-memory only). */
@Serializable
data object SendRoute

fun NavGraphBuilder.sendDestination(
    onDismiss: () -> Unit,
) {
    composable<SendRoute> {
        SendScreen(onDismiss = onDismiss)
    }
}

fun NavController.navigateToSend() {
    navigate(SendRoute)
}
