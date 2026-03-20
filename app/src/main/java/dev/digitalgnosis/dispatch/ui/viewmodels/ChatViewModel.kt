package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * ViewModel for ChatScreen — shows sessions from File Bridge.
 *
 * Fetches sessions via GET /sessions/for-dispatch on File Bridge.
 * Tapping a session opens MessagesScreen which streams via File Bridge SSE.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionRepository.SessionListResult>>(emptyList())
    val sessions: StateFlow<List<SessionRepository.SessionListResult>> = _sessions.asStateFlow()

    private val _sessionInfoList = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionInfoList: StateFlow<List<SessionInfo>> = _sessionInfoList.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    data class SessionInfo(
        val sessionId: String,
        val title: String,
        val department: String,
        val status: String,
        val lastActivity: String,
        val alias: String,
    )

    init {
        Timber.i("ChatViewModel: Initializing (File Bridge)")
        refresh()
    }

    fun selectSession(sessionId: String?) {
        _selectedSessionId.value = sessionId
        Timber.d("ChatViewModel: Selected session: %s", sessionId?.take(20) ?: "none")
    }

    fun refresh() {
        viewModelScope.launch {
            Timber.d("ChatViewModel: Refresh triggered")
            _isRefreshing.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    sessionRepository.fetchForDispatch(limit = 30)
                }
                _sessionInfoList.value = result.sessions.map { s ->
                    SessionInfo(
                        sessionId = s.sessionId,
                        title = s.summary?.take(60) ?: s.alias.ifBlank { s.sessionId.take(20) },
                        department = s.department,
                        status = s.status,
                        lastActivity = s.lastActivity,
                        alias = s.alias,
                    )
                }
                Timber.d("ChatViewModel: Loaded %d sessions from File Bridge", result.sessions.size)
            } catch (e: Exception) {
                Timber.e(e, "ChatViewModel: Refresh failed")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
