package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitalgnosis.dispatch.data.HistoryMessage
import dev.digitalgnosis.dispatch.data.HistoryRepository
import dev.digitalgnosis.dispatch.network.AudioStreamClient
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

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val fileTransferClient: FileTransferClient,
    private val audioStreamClient: AudioStreamClient
) : ViewModel() {

    private val _history = MutableStateFlow<List<HistoryMessage>>(emptyList())
    val history: StateFlow<List<HistoryMessage>> = _history.asStateFlow()

    private val _totalMessages = MutableStateFlow(0)
    val totalMessages: StateFlow<Int> = _totalMessages.asStateFlow()

    private val _knownSenders = MutableStateFlow<List<String>>(emptyList())
    val knownSenders: StateFlow<List<String>> = _knownSenders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentSearch: String? = null
    private var currentSender: String? = null
    private val PAGE_SIZE = 40

    fun loadMessages(reset: Boolean = false) {
        viewModelScope.launch {
            if (reset) {
                _isLoading.value = true
                _history.value = emptyList()
            } else {
                _isLoadingMore.value = true
            }
            _error.value = null

            val offset = if (reset) 0 else _history.value.size

            try {
                val result = withContext(Dispatchers.IO) {
                    historyRepository.fetchDispatchHistory(
                        sender = currentSender,
                        search = currentSearch,
                        limit = PAGE_SIZE,
                        offset = offset
                    )
                }
                
                if (reset) {
                    _history.value = result.messages
                    if (currentSender == null && currentSearch == null && _knownSenders.value.isEmpty()) {
                        discoverSenders()
                    }
                } else {
                    _history.value = _history.value + result.messages
                }
                _totalMessages.value = result.total
            } catch (e: Exception) {
                Timber.e(e, "HistoryViewModel: loadMessages failed")
                _error.value = e.message
            } finally {
                _isLoading.value = false
                _isLoadingMore.value = false
            }
        }
    }

    fun setSearch(query: String?) {
        currentSearch = query
        loadMessages(reset = true)
    }

    fun setSender(sender: String?) {
        currentSender = sender
        loadMessages(reset = true)
    }

    private fun discoverSenders() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    historyRepository.fetchDispatchHistory(limit = 200, offset = 0)
                }
                val senders = result.messages.map { it.sender }.distinct().sorted()
                if (senders.isNotEmpty()) {
                    _knownSenders.value = senders
                }
            } catch (e: Exception) {
                Timber.e(e, "HistoryViewModel: discoverSenders failed")
            }
        }
    }

    fun replayMessage(message: HistoryMessage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                audioStreamClient.replayMessage(
                    sender = message.sender,
                    message = message.message,
                    voice = message.voice
                )
            }
        }
    }

    fun downloadFile(url: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                fileTransferClient.downloadToStorage(url, name)
            }
        }
    }
}
