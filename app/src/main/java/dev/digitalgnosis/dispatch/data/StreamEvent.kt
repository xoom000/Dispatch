package dev.digitalgnosis.dispatch.data

/**
 * Sealed class representing events from a Claude Code streaming session.
 *
 * Used by both the File Bridge SSE relay and the Anthropic Sessions API.
 * Each variant maps to a specific SSE event type from the stream.
 */
sealed class StreamEvent {

    /** SSE connection established. */
    data class Connected(
        val streamId: String,
        val department: String,
    ) : StreamEvent()

    /** Incremental text token from content_block_delta. */
    data class Token(val text: String) : StreamEvent()

    /** Thinking/reasoning content from content_block_delta. */
    data class Thinking(val thinking: String) : StreamEvent()

    /** Tool use event — started, progress, or completed. */
    data class Tool(
        val name: String,
        val status: String,
        val toolUseId: String = "",
    ) : StreamEvent()

    /** Complete assistant text from an assistant message. */
    data class AssistantText(val text: String) : StreamEvent()

    /** Stream error event. */
    data class Error(
        val errorType: String,
        val result: String,
    ) : StreamEvent()

    /** Stream completed — final result with cost and timing. */
    data class Done(
        val result: String,
        val costUsd: Double,
        val durationMs: Long,
        val sessionId: String,
        val stopReason: String,
    ) : StreamEvent()

    /** System status event (model loaded, session started, etc). */
    data class Status(
        val status: String,
        val model: String,
        val sessionId: String,
    ) : StreamEvent()
}
