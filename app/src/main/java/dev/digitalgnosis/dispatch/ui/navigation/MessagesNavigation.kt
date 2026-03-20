package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.digitalgnosis.dispatch.ui.screens.MessagesScreen
import kotlinx.serialization.Serializable

/**
 * Route for [MessagesScreen].
 *
 * Both args are required — the screen has no meaningful default without them.
 */
@Serializable
data class MessagesRoute(
    val threadId: String,
    val department: String,
)

fun NavGraphBuilder.messagesDestination(
    onBack: () -> Unit,
) {
    composable<MessagesRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<MessagesRoute>()
        MessagesScreen(
            threadId = route.threadId,
            department = route.department,
            onBack = onBack,
        )
    }
}

fun NavController.navigateToMessages(threadId: String, department: String) {
    navigate(MessagesRoute(threadId = threadId, department = department))
}
