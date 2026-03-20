package dev.digitalgnosis.dispatch.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.digitalgnosis.dispatch.ui.screens.EventFeedScreen
import kotlinx.serialization.Serializable

/** Route for the event feed screen — no args needed. */
@Serializable
data object EventFeedRoute

fun NavGraphBuilder.eventFeedDestination(
    refreshSignal: Int = 0,
) {
    composable<EventFeedRoute> {
        EventFeedScreen(refreshSignal = refreshSignal)
    }
}

fun NavController.navigateToEventFeed() {
    navigate(EventFeedRoute)
}
