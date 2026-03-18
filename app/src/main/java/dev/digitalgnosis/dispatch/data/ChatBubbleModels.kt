package dev.digitalgnosis.dispatch.data

/**
 * Domain model for a chat bubble — one visual element in the conversation.
 *
 * Types:
 * - "nigel": Nigel's typed messages
 * - "agent": Claude's conversational text
 * - "dispatch": Dispatch audio payloads (what Nigel hears)
 * - "tool": Tool use (collapsed, shows tool name)
 *
 * This is the clean domain object. ChatBubbleEntity is the Room persistence
 * layer equivalent. Mapping between them lives in ChatBubbleRepository.
 */
data class ChatBubble(
    val type: String,
    val text: String,
    val detail: String = "",
    val sequence: Int,
    val subSeq: Int = 0,
    val timestamp: String = "",
)

/**
 * Result wrapper for chat bubble fetch operations.
 * Returned by SessionRepository.fetchChatBubbles().
 */
data class ChatBubblesResult(
    val bubbles: List<ChatBubble>,
    val sessionId: String,
    val maxSequence: Int,
    val minSequence: Int,
    val hasMore: Boolean,
    val hasEarlier: Boolean,
)
