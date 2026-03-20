package dev.digitalgnosis.dispatch.appfunctions

import androidx.appfunctions.AppFunctionSerializable

// ── Send message (cmail) ──────────────────────────────────────────────────────

/**
 * Request to send a message to one or more departments via Dispatch cmail.
 *
 * The agent (Claude) populates this when the user asks it to relay a message.
 * [invokeAgent] triggers an agent session in the target department.
 */
@AppFunctionSerializable
data class SendMessageRequest(
    /** The message body to send. Required, non-blank. */
    val message: String,
    /** Comma-separated department names, e.g. "engineering,research". */
    val departments: String,
    /** True to also invoke an AI agent in the target department. */
    val invokeAgent: Boolean = false,
)

/**
 * Result of a [SendMessageRequest].
 */
@AppFunctionSerializable
data class SendMessageResponse(
    /** True if the message was accepted by File Bridge. */
    val success: Boolean,
    /** Thread ID created for this send, or null on failure. */
    val threadId: String?,
    /** Human-readable error message if [success] is false. */
    val error: String? = null,
)

// ── Read conversation (session chat bubbles) ──────────────────────────────────

/**
 * Request to read the recent messages from a conversation session.
 */
@AppFunctionSerializable
data class GetConversationRequest(
    /** The session/thread ID to fetch. */
    val sessionId: String,
    /** Maximum number of bubbles to return. Defaults to 20. */
    val limit: Int = 20,
)

/**
 * A single message bubble returned in a [GetConversationResponse].
 *
 * Types: "nigel" (user), "agent" (Claude text), "dispatch" (audio payload), "tool".
 */
@AppFunctionSerializable
data class ConversationBubble(
    val type: String,
    val text: String,
    val timestamp: String,
    val sequence: Int,
)

/**
 * Result of a [GetConversationRequest].
 */
@AppFunctionSerializable
data class GetConversationResponse(
    val sessionId: String,
    val bubbles: List<ConversationBubble>,
    val hasEarlier: Boolean,
    val error: String? = null,
)

// ── Session list ──────────────────────────────────────────────────────────────

/**
 * Request to list active or recent sessions.
 */
@AppFunctionSerializable
data class GetSessionsRequest(
    /** Department filter, or empty string for all departments. */
    val department: String,
    /** Maximum number of sessions to return. */
    val limit: Int = 10,
)

/**
 * Summary of a single session in a [GetSessionsResponse].
 */
@AppFunctionSerializable
data class SessionSummary(
    val sessionId: String,
    val department: String,
    val summary: String,
    val status: String,
    val lastActivity: String,
    val isActive: Boolean,
)

/**
 * Result of a [GetSessionsRequest].
 */
@AppFunctionSerializable
data class GetSessionsResponse(
    val sessions: List<SessionSummary>,
    val error: String? = null,
)

// ── Pulse feed ────────────────────────────────────────────────────────────────

/**
 * Request to read recent Pulse posts.
 */
@AppFunctionSerializable
data class GetPulseFeedRequest(
    /** Channel name filter, or empty string for the global feed. */
    val channel: String,
    /** Maximum number of posts to return. */
    val limit: Int = 20,
)

/**
 * A single Pulse post in a [GetPulseFeedResponse].
 */
@AppFunctionSerializable
data class PulseFeedItem(
    val department: String,
    val message: String,
    val channel: String,
    val timestampMs: Long,
    val tags: List<String>,
)

/**
 * Result of a [GetPulseFeedRequest].
 */
@AppFunctionSerializable
data class GetPulseFeedResponse(
    val posts: List<PulseFeedItem>,
    val error: String? = null,
)

// ── Whiteboard ────────────────────────────────────────────────────────────────

/**
 * Request to read the CEO whiteboard task list.
 */
@AppFunctionSerializable
data class GetWhiteboardRequest(
    /** Filter by status: "active", "blocked", "done", "parked", or empty for all. */
    val statusFilter: String,
)

/**
 * A single whiteboard task in a [GetWhiteboardResponse].
 */
@AppFunctionSerializable
data class WhiteboardTaskItem(
    val id: String,
    val title: String,
    val assignee: String,
    val status: String,
    val priority: String,
    val threadId: String?,
    val note: String?,
)

/**
 * Result of a [GetWhiteboardRequest].
 */
@AppFunctionSerializable
data class GetWhiteboardResponse(
    val tasks: List<WhiteboardTaskItem>,
    val lastUpdated: String,
    val error: String? = null,
)

// ── Dispatch history ──────────────────────────────────────────────────────────

/**
 * Request to read the dispatch message history.
 */
@AppFunctionSerializable
data class GetDispatchHistoryRequest(
    /** Maximum number of history entries to return. */
    val limit: Int = 20,
    /** Only return messages from this sender, or empty for all senders. */
    val senderFilter: String,
)

/**
 * A single entry in the dispatch history feed.
 */
@AppFunctionSerializable
data class DispatchHistoryItem(
    val id: Int,
    val timestamp: String,
    val sender: String,
    val message: String,
    val priority: String,
    val success: Boolean,
    val threadId: String?,
)

/**
 * Result of a [GetDispatchHistoryRequest].
 */
@AppFunctionSerializable
data class GetDispatchHistoryResponse(
    val items: List<DispatchHistoryItem>,
    val error: String? = null,
)
