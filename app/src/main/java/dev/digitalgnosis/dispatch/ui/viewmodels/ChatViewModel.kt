package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for ChatScreen — shows Claude Code sessions.
 *
 * Fetches sessions from File Bridge via SessionRepository.
 * Tapping a session opens MessagesScreen with the session ID,
 * which loads chat bubbles from the session chat endpoint.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedSession = MutableStateFlow<SessionInfo?>(null)
    val selectedSession: StateFlow<SessionInfo?> = _selectedSession.asStateFlow()

    init {
        Timber.i("ChatViewModel: Initializing...")
        refresh()
    }

    fun selectSession(session: SessionInfo?) {
        val label = session?.alias?.ifBlank { null }
            ?: session?.sessionId?.take(8)
            ?: "none"
        Timber.d("ChatViewModel: Selected session: %s", label)
        _selectedSession.value = session
    }

    fun refresh() {
        viewModelScope.launch {
            Timber.d("ChatViewModel: Manual/Auto refresh triggered")
            _isRefreshing.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    sessionRepository.fetchForDispatch()
                }
                _sessions.value = result.sessions
                Timber.d("ChatViewModel: Loaded %d sessions", result.sessions.size)
            } catch (e: Exception) {
                Timber.e(e, "ChatViewModel: Refresh failed")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
