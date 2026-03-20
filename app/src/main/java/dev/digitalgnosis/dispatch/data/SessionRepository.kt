package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for Claude Code session data and agent interactions.
 *
 * All ViewModels and managers depend on this interface, never on [SessionRepositoryImpl] directly.
 * Enables mocking in unit tests without a network stack.
 */
interface SessionRepository {

    fun fetchSessions(
        dept: String? = null,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SessionListResult

    fun fetchForDispatch(dept: String? = null, limit: Int = 30): SessionListResult

    fun fetchActiveSessions(dept: String? = null): SessionListResult

    fun fetchChatBubbles(
        sessionId: String,
        limit: Int = 100,
        tail: Boolean = true,
        beforeSequence: Int = 0,
        sinceSequence: Int = 0,
    ): ChatBubblesResult

    fun fetchSessionDetail(
        sessionId: String,
        sinceSequence: Int = 0,
        limit: Int = 500,
    ): SessionDetail?

    fun sendSessionCommand(sessionId: String, command: String): Result<String>

    fun sendSlashCommand(command: String, department: String): Result<String>

    fun streamChat(
        message: String,
        department: String? = null,
        model: String? = null,
    ): Flow<StreamEvent>

    // ── Result / model types ─────────────────────────────────────────────────

    data class SessionListResult(
        val sessions: List<SessionInfo>,
        val total: Int,
    )
}
