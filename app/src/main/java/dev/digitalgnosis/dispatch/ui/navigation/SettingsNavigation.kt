package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.tts.ModelManager
import dev.digitalgnosis.dispatch.tts.TtsEngine
import dev.digitalgnosis.dispatch.ui.screens.SettingsScreen
import kotlinx.serialization.Serializable

/** Route for the Settings screen — no args needed. */
@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsDestination(
    tokenManager: TokenManager,
    modelManager: ModelManager,
    ttsEngine: TtsEngine,
    onBack: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsScreen(
            tokenManager = tokenManager,
            modelManager = modelManager,
            ttsEngine = ttsEngine,
            onBack = onBack,
        )
    }
}

fun NavController.navigateToSettings() {
    navigate(SettingsRoute)
}
