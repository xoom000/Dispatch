package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.CmailSendResult
import dev.digitalgnosis.dispatch.data.DepartmentInfo
import dev.digitalgnosis.dispatch.data.ThreadInfo
import dev.digitalgnosis.dispatch.network.FileTransferClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

import dev.digitalgnosis.dispatch.data.DispatchMessage
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.ui.SendDraft

import dev.digitalgnosis.dispatch.data.GeminiRepository
import dev.digitalgnosis.dispatch.data.GeminiUpdate

@HiltViewModel
class SendViewModel @Inject constructor(
    private val cmailRepository: CmailRepository,
    private val geminiRepository: GeminiRepository,
    private val fileTransferClient: FileTransferClient,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _departments = MutableStateFlow<List<DepartmentInfo>>(emptyList())
    val departments: StateFlow<List<DepartmentInfo>> = _departments.asStateFlow()

    private val _recentThreads = MutableStateFlow<List<ThreadInfo>>(emptyList())
    val recentThreads: StateFlow<List<ThreadInfo>> = _recentThreads.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadDepartments()
    }

    fun loadDepartments() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    cmailRepository.fetchDepartments()
                }
                _departments.value = result
            } catch (e: Exception) {
                Timber.e(e, "SendViewModel: loadDepartments failed")
            }
        }
    }

    fun loadRecentThreads(participant: String) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    cmailRepository.fetchThreads(limit = 5, participant = participant)
                }
                // Filter to threads where this dept is actually a participant
                _recentThreads.value = result.threads.filter { thread ->
                    thread.participants.any { it.equals(participant, ignoreCase = true) }
                }
            } catch (e: Exception) {
                Timber.e(e, "SendViewModel: loadRecentThreads failed")
            }
        }
    }

    fun send(
        depts: List<String>,
        message: String,
        invoke: Boolean,
        threadId: String?,
        files: List<SendDraft.DraftFile>,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isSending.value = true
            _statusText.value = if (files.isNotEmpty()) "Uploading + sending..." else "Sending..."
            
            val hasFiles = files.isNotEmpty()
            val total = depts.size
            val fileLabel = if (hasFiles) files.joinToString(", ") { it.name } else null
            val displayMsg = if (fileLabel != null) "$message [attached: $fileLabel]" else message

            try {
                if (total > 1) {
                    // Group send logic (legacy path for now)
                    var uploadFailed = false
                    // ... (rest of group logic)
                } else {
                    // Single send
                    val dept = depts.first()
                    if (dept.equals("Gemini CLI", ignoreCase = true) && !hasFiles) {
                        sendGeminiNative(dept, message, threadId, onSuccess)
                    } else {
                        sendIndividually(depts, message, displayMsg, invoke, threadId, hasFiles, files, onSuccess)
                    }
                }
            } catch (e: Exception) {
                _statusText.value = "Error: ${e.message}"
            } finally {
                _isSending.value = false
            }
        }
    }

    private suspend fun sendGeminiNative(
        dept: String,
        message: String,
        threadId: String?,
        onSuccess: () -> Unit
    ) {
        val targetThreadId = threadId ?: "gemini-native-${System.currentTimeMillis()}"
        
        // Add user message optimistically
        messageRepository.addMessage(DispatchMessage(
            sender = "You",
            message = message,
            priority = "normal",
            timestamp = "",
            isOutgoing = true,
            targetDepartment = dept,
            sessionId = targetThreadId
        ))

        _statusText.value = "Starting Gemini session..."
        var fullResponse = ""

        try {
            geminiRepository.sendNativePrompt(targetThreadId, message).collect { update ->
                when (update) {
                    is GeminiUpdate.Thought -> {
                        _statusText.value = update.text
                    }
                    is GeminiUpdate.MessageChunk -> {
                        fullResponse += update.text
                        _statusText.value = "Gemini is responding..."
                    }
                    is GeminiUpdate.Error -> {
                        _error.value = update.message
                    }
                }
            }

            if (fullResponse.isNotBlank()) {
                messageRepository.addMessage(DispatchMessage(
                    sender = dept,
                    message = fullResponse,
                    priority = "normal",
                    timestamp = "",
                    isOutgoing = false,
                    sessionId = targetThreadId
                ))
            }
            _statusText.value = "Message delivered"
            onSuccess()
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _statusText.value = null
        }
    }

    private suspend fun sendIndividually(
        depts: List<String>,
        message: String,
        displayMsg: String,
        invoke: Boolean,
        threadId: String?,
        hasFiles: Boolean,
        files: List<SendDraft.DraftFile>,
        onSuccess: () -> Unit
    ) {
        var succeeded = 0
        var failed = 0

        for (dept in depts) {
            var uploadFailed = false
            if (hasFiles) {
                for (file in files) {
                    val ok = withContext(Dispatchers.IO) {
                        fileTransferClient.uploadToDepartment(file.bytes, file.name, dept, message)
                    }
                    if (!ok) { uploadFailed = true; break }
                }
                if (uploadFailed) { failed++; continue }
            }

            val sendTime = System.currentTimeMillis()
            val agentType = if (dept.equals("Gemini CLI", ignoreCase = true)) "gemini" else null
            val result = withContext(Dispatchers.IO) {
                cmailRepository.sendCmail(dept, message, invoke = invoke, threadId = threadId, agentType = agentType)
            }
            if (result.isSuccess) {
                val res = result.getOrThrow()
                succeeded++
                messageRepository.addMessage(DispatchMessage(
                    sender = "You",
                    message = displayMsg,
                    priority = "normal",
                    timestamp = "",
                    isOutgoing = true,
                    targetDepartment = dept,
                    fileNames = if (hasFiles) files.map { it.name } else emptyList(),
                    invoked = res.invoked,
                    invokedDepartment = if (res.invoked) (res.department ?: dept) else null,
                    invokedAt = if (res.invoked) sendTime else 0L,
                    sessionId = res.sessionId,
                ))
            } else {
                failed++
            }
        }

        _statusText.value = formatSendStatus(succeeded, failed, depts)
        if (succeeded > 0) onSuccess()
    }

    private fun formatSendStatus(succeeded: Int, failed: Int, depts: List<String>): String {
        return when {
            failed == 0 && succeeded == 1 -> "Sent to ${depts.first()}"
            failed == 0 -> "Sent to $succeeded departments"
            succeeded == 0 -> "Failed: all $failed departments"
            else -> "Sent to $succeeded, failed $failed"
        }
    }
}
