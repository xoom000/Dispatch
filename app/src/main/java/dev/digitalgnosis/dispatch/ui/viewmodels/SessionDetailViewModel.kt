package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.SessionDetail
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
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _sessionDetail = MutableStateFlow<SessionDetail?>(null)
    val sessionDetail: StateFlow<SessionDetail?> = _sessionDetail.asStateFlow()

    private val _records = MutableStateFlow<List<SessionRecord>>(emptyList())
    val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: kotlinx.coroutines.Job? = null

    fun loadSession(sessionId: String, initialRecords: List<SessionRecord> = emptyList()) {
        _records.value = initialRecords
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val detail = withContext(Dispatchers.IO) {
                    sessionRepository.fetchSessionDetail(sessionId, sinceSequence = 0)
                }
                if (detail != null) {
                    _sessionDetail.value = detail
                    _records.value = detail.records
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startLiveWatching(sessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val currentMax = _sessionDetail.value?.maxSequence ?: 0
                    val newDetail = withContext(Dispatchers.IO) {
                        sessionRepository.fetchSessionDetail(sessionId, sinceSequence = currentMax)
                    }
                    if (newDetail != null) {
                        _sessionDetail.value = newDetail
                        if (newDetail.records.isNotEmpty()) {
                            _records.value = _records.value + newDetail.records
                        }
                        if (newDetail.session.status == "completed") {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "SessionDetailViewModel: polling failed")
                }
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
