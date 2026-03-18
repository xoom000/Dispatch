package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.EventRepository
import dev.digitalgnosis.dispatch.data.OrchestratorEvent
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val eventRepository: EventRepository
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _totalSessions = MutableStateFlow(0)
    val totalSessions: StateFlow<Int> = _totalSessions.asStateFlow()

    private val _events = MutableStateFlow<List<OrchestratorEvent>>(emptyList())
    val events: StateFlow<List<OrchestratorEvent>> = _events.asStateFlow()

    private val _totalEvents = MutableStateFlow(0)
    val totalEvents: StateFlow<Int> = _totalEvents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentEventFilter: String? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Parallel fetch
                val sessionsJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        sessionRepository.fetchSessions(limit = 50)
                    }
                    _sessions.value = result.sessions
                    _totalSessions.value = result.total
                }
                val eventsJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        eventRepository.fetchEvents(limit = 100, eventType = currentEventFilter)
                    }
                    _events.value = result.events
                    _totalEvents.value = result.total
                }
                sessionsJob.join()
                eventsJob.join()
            } catch (e: Exception) {
                Timber.e(e, "AgentsViewModel: refresh failed")
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    sessionRepository.fetchSessions(limit = 50)
                }
                _sessions.value = result.sessions
                _totalSessions.value = result.total
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setEventFilter(eventType: String?) {
        currentEventFilter = eventType
        refreshEvents()
    }

    fun refreshEvents() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    eventRepository.fetchEvents(limit = 100, eventType = currentEventFilter)
                }
                _events.value = result.events
                _totalEvents.value = result.total
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendSessionCommand(sessionId: String, command: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                sessionRepository.sendSessionCommand(sessionId, command)
            }
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message
            } else {
                refreshSessions()
            }
        }
    }
}
