package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.config.AnthropicAuthManager
import dev.digitalgnosis.dispatch.network.SessionsApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for ChatScreen — shows sessions from the Anthropic Sessions API.
 *
 * Fetches sessions via GET /v1/sessions (OAuth-authenticated).
 * Tapping a session opens MessagesScreen which streams via Sessions API.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionsApiClient: SessionsApiClient,
    private val authManager: AnthropicAuthManager,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionsApiClient.SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionsApiClient.SessionSummary>> = _sessions.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedSession = MutableStateFlow<SessionsApiClient.SessionSummary?>(null)
    val selectedSession: StateFlow<SessionsApiClient.SessionSummary?> = _selectedSession.asStateFlow()

    private val _authStatus = MutableStateFlow("")
    val authStatus: StateFlow<String> = _authStatus.asStateFlow()

    init {
        Timber.i("ChatViewModel: Initializing (Sessions API)")
        // Always refresh credentials from File Bridge before first load
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = authManager.fetchFromFileBridge(
                dev.digitalgnosis.dispatch.config.TailscaleConfig.FILE_BRIDGE_SERVER
            )
            if (fetched) {
                sessionsApiClient.discoverBridgeEnvironment()?.let {
                    authManager.bridgeEnvironmentId = it
                }
                Timber.i("ChatViewModel: refreshed OAuth from File Bridge")
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun selectSession(session: SessionsApiClient.SessionSummary?) {
        val label = session?.title?.take(30) ?: "none"
        Timber.d("ChatViewModel: Selected session: %s", label)
        _selectedSession.value = session
    }

    fun refresh() {
        viewModelScope.launch {
            Timber.d("ChatViewModel: Refresh triggered")
            _isRefreshing.value = true
            try {
                if (!authManager.isAuthenticated) {
                    _authStatus.value = "Not authenticated — configure OAuth in Settings"
                    _sessions.value = emptyList()
                    return@launch
                }

                _authStatus.value = ""
                val result = withContext(Dispatchers.IO) {
                    sessionsApiClient.listSessions()
                }
                _sessions.value = result
                Timber.d("ChatViewModel: Loaded %d sessions from Sessions API", result.size)
            } catch (e: Exception) {
                Timber.e(e, "ChatViewModel: Refresh failed")
                _authStatus.value = "Failed to load sessions: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
