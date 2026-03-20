package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.ChatScreen
import kotlinx.serialization.Serializable

/** Route for the Chat tab — no args needed. */
@Serializable
data object ChatRoute

fun NavGraphBuilder.chatDestination(
    onComposeNew: () -> Unit,
    onOpenConversation: (sessionId: String, department: String) -> Unit,
) {
    composable<ChatRoute> {
        ChatScreen(
            onComposeNew = onComposeNew,
            onOpenConversation = onOpenConversation,
        )
    }
}

fun NavController.navigateToChat() {
    navigate(ChatRoute) {
        launchSingleTop = true
    }
}
