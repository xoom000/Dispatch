package dev.digitalgnosis.dispatch.ui.rootnav

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.network.SseConnectionService
import dev.digitalgnosis.dispatch.ui.base.BaseViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

/**
 * Manages root-level navigation state for the Dispatch app.
 *
 * The state machine is intentionally simple — Dispatch has no traditional
 * auth/onboarding flow; the API key is compile-time. State tracks whether the
 * initial splash has resolved and, in future, could gate on a first-run setup.
 *
 * Pattern: Bitwarden RootNavViewModel.kt
 */
@HiltViewModel
class RootNavViewModel @Inject constructor() :
    BaseViewModel<RootNavState, Unit, RootNavAction>(
        initialState = RootNavState.Splash,
    ) {

    init {
        SseConnectionService.connectionState
            .onEach { _ ->
                sendAction(RootNavAction.Internal.ConnectionStateReceive)
            }
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: RootNavAction) {
        when (action) {
            is RootNavAction.Internal.ConnectionStateReceive -> handleConnectionStateReceive()
        }
    }

    private fun handleConnectionStateReceive() {
        // Once we get any connection state update the service is running.
        // Transition from Splash → Main on first update.
        if (mutableStateFlow.value == RootNavState.Splash) {
            mutableStateFlow.value = RootNavState.Main
        }
    }
}

// ── State ──────────────────────────────────────────────────────────────────────

sealed class RootNavState : Parcelable {
    /** Initial loading — service is starting up. */
    @Parcelize
    data object Splash : RootNavState()

    /** Normal app use — full bottom-nav scaffold is visible. */
    @Parcelize
    data object Main : RootNavState()
}

// ── Actions ────────────────────────────────────────────────────────────────────

sealed class RootNavAction {
    sealed class Internal : RootNavAction() {
        data object ConnectionStateReceive : Internal()
    }
}
