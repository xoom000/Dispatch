package dev.digitalgnosis.dispatch.playback

import dev.digitalgnosis.dispatch.data.CmailOutboxItem
import dev.digitalgnosis.dispatch.data.CmailOutboxRepository
import dev.digitalgnosis.dispatch.data.CmailRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates voice reply from the dispatch system (earbud gesture) to the cmail system.
 *
 * DispatchPlaybackService calls this coordinator when the user double-taps earbuds
 * and completes voice recognition. The coordinator sends the cmail and logs the outbox
 * item — DispatchPlaybackService has no direct knowledge of CmailRepository.
 */
@Singleton
class VoiceReplyCoordinator @Inject constructor(
    private val cmailRepository: CmailRepository,
    private val cmailOutboxRepository: CmailOutboxRepository,
) {

    /**
     * Send a voice reply to [department] in [threadId].
     *
     * @return the session ID returned by File Bridge, or null if the send failed.
     */
    suspend fun sendVoiceReply(
        department: String,
        message: String,
        threadId: String?,
    ): String? {
        return try {
            val result = cmailRepository.sendCmail(
                department = department,
                message = message,
                subject = "Voice reply from Nigel",
                invoke = true,
                threadId = threadId,
            )
            if (result.isSuccess) {
                val res = result.getOrThrow()
                val sentAt = System.currentTimeMillis()
                cmailOutboxRepository.add(CmailOutboxItem(
                    department = department,
                    message = message,
                    sentAt = sentAt,
                    invoked = res.invoked,
                    invokedDepartment = if (res.invoked) (res.department ?: department) else null,
                    invokedAt = if (res.invoked) sentAt else 0L,
                    threadId = threadId,
                    sessionId = res.sessionId,
                ))
                res.sessionId
            } else {
                val e = result.exceptionOrNull()
                Timber.e(e, "VoiceReplyCoordinator: send failed to %s", department)
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "VoiceReplyCoordinator: unexpected error for %s", department)
            null
        }
    }
}
