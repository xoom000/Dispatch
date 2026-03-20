package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.GeminiWorkspaceScreen
import kotlinx.serialization.Serializable

/** Route for the Gemini Workspace tab — no args needed. */
@Serializable
data object GeminiRoute

fun NavGraphBuilder.geminiDestination() {
    composable<GeminiRoute> {
        GeminiWorkspaceScreen()
    }
}

fun NavController.navigateToGemini() {
    navigate(GeminiRoute) {
        launchSingleTop = true
    }
}
