package dev.digitalgnosis.dispatch.data

/**
 * Result of a cmail send operation.
 * Returned by POST /cmail/send.
 */
data class CmailSendResult(
    val success: Boolean,
    val message: String,
    val invoked: Boolean = false,
    val department: String? = null,
    val sessionId: String? = null,
)

/**
 * Voice map configuration — department-to-voice assignments and available voices.
 * Returned by GET /config/voice-map.
 */
data class VoiceMapResult(
    val voiceMap: Map<String, String> = emptyMap(),
    val availableVoices: List<String> = emptyList(),
)

/**
 * Department/channel available for Cmail routing.
 * Returned by GET /cmail/departments.
 */
data class DepartmentInfo(
    val name: String,
    val description: String? = null,
    val voice: String? = null,
    val agentType: String? = null,
) {
    /** Display name falls back to capitalized name. */
    val displayName: String get() = name.replaceFirstChar { it.uppercase() }
}

/**
 * Summary of a cmail thread — used in the thread list view.
 * Matches the shape returned by GET /cmail/threads.
 */
data class ThreadInfo(
    val threadId: String,
    val subject: String,
    val participants: List<String>,
    val messageCount: Int,
    val createdAt: String,
    val lastActivity: String,
    val lastSender: String? = null,
    val lastMessagePreview: String? = null,
)

/**
 * Full thread detail with all messages.
 * Matches the shape returned by GET /cmail/threads/{thread_id}.
 */
data class ThreadDetail(
    val threadId: String,
    val subject: String,
    val participants: List<String>,
    val messageCount: Int,
    val createdAt: String,
    val lastActivity: String,
    val messages: List<ThreadMessage>,
)

/**
 * A single message within a thread.
 * Matches the messages array in the thread detail response.
 */
data class ThreadMessage(
    val messageId: String,
    val threadId: String,
    val position: Int,
    val sender: String,
    val recipient: String,
    val cc: String? = null,
    val subject: String,
    val body: String,
    val priority: String,
    val delivery: String,
    val read: Boolean,
    val createdAt: String,
    val attachments: String? = null,
) {
    /** True if Nigel sent this message. */
    val isFromNigel: Boolean get() = sender.equals("nigel", ignoreCase = true)
}
