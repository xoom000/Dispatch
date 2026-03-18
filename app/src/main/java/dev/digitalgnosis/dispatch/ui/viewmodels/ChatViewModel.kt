package dev.digitalgnosis.dispatch.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.data.SyncManager
import dev.digitalgnosis.dispatch.data.ThreadDao
import dev.digitalgnosis.dispatch.data.ThreadInfo
import dev.digitalgnosis.dispatch.data.toDomainModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val threadDao: ThreadDao,
    private val syncManager: SyncManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    // PHASE 2: UI Disconnect. The UI only observes the local Room database.
    val threads: StateFlow<List<ThreadInfo>> = threadDao.getAllThreadsFlow()
        .map { entities -> 
            Timber.d("ChatViewModel: DB emission — %d threads", entities.size)
            entities.map { it.toDomainModel() } 
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _selectedThread = MutableStateFlow<ThreadInfo?>(null)
    val selectedThread: StateFlow<ThreadInfo?> = _selectedThread.asStateFlow()

    init {
        Timber.i("ChatViewModel: Initializing...")
        refresh()
        
        // C2 Fix: Observe refresh events with debounce
        viewModelScope.launch {
            messageRepository.threadRefreshEvents.collectLatest { threadId ->
                Timber.i("ChatViewModel: Received refresh event for %s. Debouncing 500ms...", threadId)
                delay(500)
                refresh()
            }
        }
    }

    fun selectThread(thread: ThreadInfo?) {
        Timber.d("ChatViewModel: Selected thread: %s", thread?.threadId ?: "none")
        _selectedThread.value = thread
    }

    fun refresh() {
        viewModelScope.launch {
            Timber.d("ChatViewModel: Manual/Auto refresh triggered")
            _isRefreshing.value = true
            try {
                // PHASE 3: Network writes to Room. Flow automatically updates UI.
                syncManager.syncThreads()
            } catch (e: Exception) {
                Timber.e(e, "ChatViewModel: Sync failed")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
