package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.SessionInfo
import dev.digitalgnosis.dispatch.data.SessionRecord
import dev.digitalgnosis.dispatch.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()

    private val _records = MutableStateFlow<List<SessionRecord>>(emptyList())
    val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _discoveryError = MutableStateFlow<String?>(null)
    val discoveryError: StateFlow<String?> = _discoveryError.asStateFlow()

    private val _pollError = MutableStateFlow<String?>(null)
    val pollError: StateFlow<String?> = _pollError.asStateFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null
    private var discoveryJob: kotlinx.coroutines.Job? = null

    fun startDiscovery(department: String, invokedAt: Long, sessionId: String? = null) {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _discovering.value = true
            _discoveryError.value = null

            if (sessionId != null) {
                _sessionInfo.value = SessionInfo(
                    sessionId = sessionId,
                    department = department,
                    projectKey = "",
                    summary = null,
                    model = null,
                    startedAt = "",
                    lastActivity = "",
                    recordCount = 0,
                    status = "active",
                    gitBranch = null,
                    cwd = null,
                )
                _discovering.value = false
                startWatching(sessionId)
                return@launch
            }

            val maxAttempts = 40
            var attempt = 0
            while (attempt < maxAttempts) {
                attempt++
                try {
                    val activeResult = withContext(Dispatchers.IO) {
                        sessionRepository.fetchActiveSessions(dept = department)
                    }

                    if (activeResult.sessions.isNotEmpty()) {
                        val tier1Window = 300_000L
                        val nearby = activeResult.sessions.filter { session ->
                            val startMs = parseIsoToEpoch(session.startedAt)
                            startMs > 0 && kotlin.math.abs(startMs - invokedAt) <= tier1Window
                        }
                        val best = nearby.minByOrNull { session ->
                            kotlin.math.abs(parseIsoToEpoch(session.startedAt) - invokedAt)
                        }
                        if (best != null) {
                            _sessionInfo.value = best
                            _discovering.value = false
                            startWatching(best.sessionId)
                            return@launch
                        }
                    }

                    if (attempt >= 5) {
                        val recentResult = withContext(Dispatchers.IO) {
                            sessionRepository.fetchSessions(dept = department, limit = 10)
                        }
                        val windowMs = 600_000L
                        val windowMatch = recentResult.sessions
                            .filter { session ->
                                val startMs = parseIsoToEpoch(session.startedAt)
                                startMs > 0 && kotlin.math.abs(startMs - invokedAt) <= windowMs
                            }
                            .minByOrNull { session ->
                                kotlin.math.abs(parseIsoToEpoch(session.startedAt) - invokedAt)
                            }

                        if (windowMatch != null) {
                            _sessionInfo.value = windowMatch
                            _discovering.value = false
                            startWatching(windowMatch.sessionId)
                            return@launch
                        }

                        if (attempt >= 10 && recentResult.sessions.isNotEmpty()) {
                            val mostRecent = recentResult.sessions.first()
                            _sessionInfo.value = mostRecent
                            _discovering.value = false
                            startWatching(mostRecent.sessionId)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Discovery attempt $attempt failed")
                }
                delay(3000)
            }

            _discoveryError.value = "No session found for $department"
            _discovering.value = false
        }
    }

    private fun startWatching(sessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var maxSequence = 0
            
            // Initial load
            try {
                val detail = withContext(Dispatchers.IO) {
                    sessionRepository.fetchSessionDetail(sessionId, sinceSequence = 0)
                }
                if (detail != null) {
                    _records.value = detail.records
                    _sessionInfo.value = detail.session
                    maxSequence = detail.maxSequence
                }
            } catch (e: Exception) {
                _pollError.value = "Failed to load session: ${e.message}"
            }

            // Polling loop
            while (_sessionInfo.value?.status == "active") {
                delay(2500)
                try {
                    val detail = withContext(Dispatchers.IO) {
                        sessionRepository.fetchSessionDetail(sessionId, sinceSequence = maxSequence)
                    }
                    if (detail != null) {
                        if (detail.records.isNotEmpty()) {
                            _records.value = _records.value + detail.records
                            maxSequence = detail.maxSequence
                        }
                        _sessionInfo.value = detail.session
                        _pollError.value = null
                    }
                } catch (e: Exception) {
                    _pollError.value = "Connection issue: ${e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun parseIsoToEpoch(iso: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
        pollingJob?.cancel()
    }
}
