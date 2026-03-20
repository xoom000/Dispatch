package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event bus for real-time cmail SSE signals.
 *
 * Emits thread IDs whenever a cmail_message or cmail_reply SSE event arrives
 * from File Bridge. Observers (e.g. ThreadsViewModel) refresh the affected thread
 * without polling.
 *
 * Emitter: SseConnectionService (routes cmail events here)
 * Observers: ThreadsViewModel (refreshes thread list)
 */
@Singleton
class CmailEventBus @Inject constructor() {

    private val _threadUpdates = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 16)
    val threadUpdates: SharedFlow<String> = _threadUpdates.asSharedFlow()

    fun emit(threadId: String) {
        _threadUpdates.tryEmit(threadId)
    }
}
