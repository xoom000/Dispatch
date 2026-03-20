package dev.digitalgnosis.dispatch.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Lifecycle-aware event consumer for [BaseViewModel.eventFlow].
 *
 * Events are only processed when the screen is at least RESUMED, preventing duplicate
 * navigation calls when a screen is in the back stack or paused. Place this in any
 * Composable that needs to respond to ViewModel events:
 *
 * ```kotlin
 * EventsEffect(viewModel = viewModel) { event ->
 *     when (event) {
 *         is MyEvent.NavigateToDetail -> navController.navigateToDetail(event.id)
 *         is MyEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
 *     }
 * }
 * ```
 */
@Composable
fun <E> EventsEffect(
    viewModel: BaseViewModel<*, E, *>,
    lifecycleOwner: Lifecycle = LocalLifecycleOwner.current.lifecycle,
    handler: suspend (E) -> Unit,
) {
    LaunchedEffect(key1 = Unit) {
        viewModel
            .eventFlow
            .onEach { event ->
                // Only deliver events when the screen is resumed to prevent duplicate navigation.
                if (lifecycleOwner.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    handler(event)
                }
            }
            .launchIn(this)
    }
}
