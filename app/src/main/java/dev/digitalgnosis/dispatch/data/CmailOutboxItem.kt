package dev.digitalgnosis.dispatch.data

/**
 * Domain model for an outgoing cmail message sent by Nigel from the app.
 *
 * Represents exactly one sent cmail: which department received it, whether an agent
 * was invoked, and the session context returned by File Bridge.
 */
data class CmailOutboxItem(
    val department: String,
    val message: String,
    val sentAt: Long = System.currentTimeMillis(),
    val invoked: Boolean = false,
    val invokedDepartment: String? = null,
    val invokedAt: Long = 0L,
    val threadId: String? = null,
    val sessionId: String? = null,
    /** Display names of any file attachments included with this send. */
    val fileNames: List<String> = emptyList(),
)
