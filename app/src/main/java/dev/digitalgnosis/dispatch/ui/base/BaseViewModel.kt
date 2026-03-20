package dev.digitalgnosis.dispatch.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Base [ViewModel] that enforces unidirectional data flow (UDF) for all Dispatch ViewModels.
 *
 * - [S] = State: the complete, immutable UI state. Implement @Parcelize for process death survival.
 * - [E] = Event: one-shot side effects (navigation, toasts). Each event is consumed by one observer.
 * - [A] = Action: things the ViewModel handles — user interactions and internal async results.
 *
 * State mutations ONLY happen synchronously inside [handleAction]. Async work launches a coroutine
 * and posts an internal action back through [actionChannel] when done. This makes all state
 * transitions auditable, sequential, and race-condition-free.
 */
abstract class BaseViewModel<S, E, A>(
    initialState: S,
) : ViewModel() {

    protected val mutableStateFlow: MutableStateFlow<S> = MutableStateFlow(initialState)
    private val eventChannel: Channel<E> = Channel(capacity = Channel.UNLIMITED)
    private val internalActionChannel: Channel<A> = Channel(capacity = Channel.UNLIMITED)

    /** Returns the current state synchronously. */
    protected val state: S get() = mutableStateFlow.value

    /** StateFlow of UI state — collect in Composables via collectAsStateWithLifecycle(). */
    val stateFlow: StateFlow<S> = mutableStateFlow.asStateFlow()

    /**
     * Flow of one-shot events. Single-consumer semantics — only one collector receives each event.
     * Typically consumed by [EventsEffect] in the associated Composable.
     */
    val eventFlow: Flow<E> = eventChannel.receiveAsFlow()

    /**
     * External send channel for posting actions. ViewModels expose [trySendAction] for Composables.
     * Internal async work uses [sendAction] (suspend) to ensure delivery.
     */
    val actionChannel: SendChannel<A> = internalActionChannel

    init {
        viewModelScope.launch {
            internalActionChannel
                .consumeAsFlow()
                .collect { action ->
                    handleAction(action)
                }
        }
    }

    /**
     * Synchronous action handler — the ONLY place state is mutated.
     *
     * For actions requiring async work, launch a coroutine here and call [sendAction] with an
     * internal result action when the work completes. Never update [mutableStateFlow] inside
     * a coroutine directly.
     */
    protected abstract fun handleAction(action: A)

    /** Post an action from a Composable (fire-and-forget, non-blocking). */
    fun trySendAction(action: A) {
        internalActionChannel.trySend(action)
    }

    /** Post an action from inside a coroutine (suspends until delivered). */
    protected suspend fun sendAction(action: A) {
        internalActionChannel.send(action)
    }

    /** Emit a one-shot event to the UI (e.g., navigate, show snackbar). */
    protected fun sendEvent(event: E) {
        viewModelScope.launch { eventChannel.send(event) }
    }
}
