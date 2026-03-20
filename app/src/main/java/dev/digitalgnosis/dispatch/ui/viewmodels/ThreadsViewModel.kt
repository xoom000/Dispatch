package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.DepartmentInfo
import dev.digitalgnosis.dispatch.data.ThreadDetail
import dev.digitalgnosis.dispatch.data.ThreadInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

import dev.digitalgnosis.dispatch.data.CmailEventBus
import dev.digitalgnosis.dispatch.data.GeminiRepository
import dev.digitalgnosis.dispatch.data.GeminiUpdate

@HiltViewModel
class ThreadsViewModel @Inject constructor(
    private val cmailRepository: CmailRepository,
    private val geminiRepository: GeminiRepository,
    private val cmailEventBus: CmailEventBus,
) : ViewModel() {

    private val _threads = MutableStateFlow<List<ThreadInfo>>(emptyList())
    val threads: StateFlow<List<ThreadInfo>> = _threads.asStateFlow()

    private val _departments = MutableStateFlow<List<DepartmentInfo>>(emptyList())
    val departments: StateFlow<List<DepartmentInfo>> = _departments.asStateFlow()

    private val _currentThread = MutableStateFlow<ThreadDetail?>(null)
    val currentThread: StateFlow<ThreadDetail?> = _currentThread.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _agentThoughts = MutableStateFlow("")
    val agentThoughts: StateFlow<String> = _agentThoughts.asStateFlow()

    init {
        loadDepartments()
        refreshThreads()

        // Auto-refresh thread list when a cmail SSE event arrives
        viewModelScope.launch {
            cmailEventBus.threadUpdates.collect { threadId ->
                Timber.d("ThreadsVM: cmail event for thread %s — refreshing", threadId.take(8))
                refreshThreads()
            }
        }
    }

    fun loadDepartments() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    cmailRepository.fetchDepartments()
                }
                _departments.value = result
            } catch (e: Exception) {
                Timber.e(e, "ThreadsViewModel: loadDepartments failed")
            }
        }
    }

    fun refreshThreads(participant: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    cmailRepository.fetchThreads(limit = 50, participant = participant)
                }
                _threads.value = result.threads
                Timber.d("ThreadsVM: refreshThreads — %d threads (participant=%s)", result.threads.size, participant)
            } catch (e: Exception) {
                Timber.e(e, "ThreadsVM: refreshThreads failed")
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadThreadDetail(threadId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    cmailRepository.fetchThreadDetail(threadId)
                }
                _currentThread.value = result
                if (result != null) {
                    Timber.d("ThreadsVM: loadThreadDetail — %d messages for %s",
                        result.messages.size, threadId.take(8))
                } else {
                    Timber.w("ThreadsVM: loadThreadDetail — null result for %s", threadId.take(8))
                }
            } catch (e: Exception) {
                Timber.e(e, "ThreadsVM: loadThreadDetail failed for %s", threadId.take(8))
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun replyToThread(threadId: String, department: String, message: String, invoke: Boolean = true) {
        viewModelScope.launch {
            // If it's Gemini CLI, use the high-fidelity native path
            if (department.equals("Gemini CLI", ignoreCase = true)) {
                Timber.d("ThreadsVM: replyToThread — routing to Gemini native path (thread=%s)", threadId.take(8))
                sendGeminiNative(threadId, department, message)
                return@launch
            }

            // Legacy Cmail path for others
            Timber.d("ThreadsVM: replyToThread — dept=%s, thread=%s", department, threadId.take(8))
            val result = withContext(Dispatchers.IO) {
                cmailRepository.replyToThread(threadId, department, message, invoke)
            }
            if (result.isSuccess) {
                Timber.i("ThreadsVM: replyToThread — success, refreshing thread detail")
                loadThreadDetail(threadId)
            } else {
                Timber.e(result.exceptionOrNull(), "ThreadsVM: replyToThread failed for %s", threadId.take(8))
                _error.value = result.exceptionOrNull()?.message
            }
        }
    }

    private suspend fun sendGeminiNative(threadId: String, department: String, message: String) {
        _agentThoughts.value = "Starting Gemini session..."

        try {
            geminiRepository.sendNativePrompt(threadId, message).collect { update ->
                when (update) {
                    is GeminiUpdate.Thought -> {
                        _agentThoughts.value = update.text
                    }
                    is GeminiUpdate.MessageChunk -> {
                        _agentThoughts.value = "" // Clear once output starts
                    }
                    is GeminiUpdate.Error -> {
                        Timber.e("ThreadsVM: Gemini stream error — %s", update.message)
                        _error.value = update.message
                    }
                }
            }
            // Refresh thread detail to get the final persisted messages
            Timber.i("ThreadsVM: sendGeminiNative — stream complete, refreshing thread %s", threadId.take(8))
            loadThreadDetail(threadId)
        } catch (e: Exception) {
            Timber.e(e, "ThreadsVM: sendGeminiNative failed for thread %s", threadId.take(8))
            _error.value = e.message
        } finally {
            _agentThoughts.value = ""
        }
    }
}
