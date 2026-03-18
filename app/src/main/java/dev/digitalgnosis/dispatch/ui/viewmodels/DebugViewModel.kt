package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.data.ActiveSessionInfo
import dev.digitalgnosis.dispatch.data.DebugRepository
import dev.digitalgnosis.dispatch.data.LogFileMeta
import dev.digitalgnosis.dispatch.data.ServerHealth
import dev.digitalgnosis.dispatch.data.ServerLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val debugRepository: DebugRepository
) : ViewModel() {

    // ── Hook Router tab ─────────────────────────────────────────────
    private val _hookRouterLogs = MutableStateFlow<List<ServerLogEntry>>(emptyList())
    val hookRouterLogs: StateFlow<List<ServerLogEntry>> = _hookRouterLogs.asStateFlow()

    // ── cmail Hooks tab ─────────────────────────────────────────────
    private val _cmailHooksLogs = MutableStateFlow<List<ServerLogEntry>>(emptyList())
    val cmailHooksLogs: StateFlow<List<ServerLogEntry>> = _cmailHooksLogs.asStateFlow()

    // ── Sessions tab ────────────────────────────────────────────────
    private val _activeSessions = MutableStateFlow<List<ActiveSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<ActiveSessionInfo>> = _activeSessions.asStateFlow()

    // ── System tab ──────────────────────────────────────────────────
    private val _logFiles = MutableStateFlow<List<LogFileMeta>>(emptyList())
    val logFiles: StateFlow<List<LogFileMeta>> = _logFiles.asStateFlow()

    private val _serverHealth = MutableStateFlow<ServerHealth?>(null)
    val serverHealth: StateFlow<ServerHealth?> = _serverHealth.asStateFlow()

    // ── Loading state ───────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Load data for a specific tab. Only fetches what's needed.
     */
    fun loadTab(tabIndex: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (tabIndex) {
                    1 -> loadHookRouter()
                    2 -> loadCmailHooks()
                    3 -> loadSessions()
                    4 -> loadSystem()
                }
            } catch (e: Exception) {
                Timber.e(e, "DebugVM: loadTab($tabIndex) failed")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadHookRouter() {
        val result = withContext(Dispatchers.IO) {
            debugRepository.fetchLogTail("hook-router", lines = 200)
        }
        _hookRouterLogs.value = result.entries
    }

    private suspend fun loadCmailHooks() {
        val result = withContext(Dispatchers.IO) {
            debugRepository.fetchLogTail("cmail-hooks", lines = 200)
        }
        _cmailHooksLogs.value = result.entries
    }

    private suspend fun loadSessions() {
        val result = withContext(Dispatchers.IO) {
            debugRepository.fetchActiveSessions()
        }
        _activeSessions.value = result
    }

    private suspend fun loadSystem() {
        val logFilesResult = withContext(Dispatchers.IO) {
            debugRepository.fetchLogIndex()
        }
        _logFiles.value = logFilesResult

        val healthResult = withContext(Dispatchers.IO) {
            debugRepository.fetchHealth()
        }
        _serverHealth.value = healthResult
    }
}
