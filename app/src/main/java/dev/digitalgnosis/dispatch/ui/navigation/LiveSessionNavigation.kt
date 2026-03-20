package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.digitalgnosis.dispatch.ui.screens.LiveSessionScreen
import kotlinx.serialization.Serializable

/**
 * Route for [LiveSessionScreen].
 *
 * [invokedAt] is a timestamp millis used to key the session; [sessionId] is
 * optional when the session is being discovered after invocation.
 */
@Serializable
data class LiveSessionRoute(
    val department: String,
    val invokedAt: Long,
    val sessionId: String? = null,
)

fun NavGraphBuilder.liveSessionDestination(
    onBack: () -> Unit,
) {
    composable<LiveSessionRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<LiveSessionRoute>()
        LiveSessionScreen(
            department = route.department,
            invokedAt = route.invokedAt,
            sessionId = route.sessionId,
            onBack = onBack,
        )
    }
}

fun NavController.navigateToLiveSession(
    department: String,
    invokedAt: Long,
    sessionId: String? = null,
) {
    navigate(LiveSessionRoute(department = department, invokedAt = invokedAt, sessionId = sessionId))
}
