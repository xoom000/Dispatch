package dev.digitalgnosis.dispatch.data

/**
 * Summary of a Claude Code session -- used in the session list view.
 * Matches the shape returned by GET /sessions.
 */
data class SessionInfo(
    val sessionId: String,
    val department: String,
    val projectKey: String,
    val summary: String?,
    val model: String?,
    val startedAt: String,
    val lastActivity: String,
    val recordCount: Int,
    val status: String,
    val gitBranch: String?,
    val cwd: String?,
    val contextTokens: Int = 0,
    val contextPct: Double = 0.0,
    val alias: String = "",
    val isActive: Boolean = false,
)

/**
 * Full session detail with records.
 * Matches the shape returned by GET /sessions/{session_id}.
 */
data class SessionDetail(
    val session: SessionInfo,
    val records: List<SessionRecord>,
    val totalRecords: Int,
    val maxSequence: Int,
    val hasMore: Boolean = false,
)

/**
 * A single record within a session.
 * Matches the records array in the session detail response.
 */
data class SessionRecord(
    val sequence: Int,
    val agentId: String,
    val recordType: String,
    val timestamp: String,
    val model: String?,
    val contentText: String?,
    val toolName: String?,
    val toolInput: String?,
    val toolStatus: String?,
    val isError: Boolean,
    val tokensIn: Int,
    val tokensOut: Int,
)

/**
 * Aggregated debug stats for a session.
 * Matches GET /sessions/{session_id}/debug.
 */
data class SessionDebugInfo(
    val recordCount: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val modelsUsed: List<String>,
    val toolCalls: Map<String, Int>,
    val errorCount: Int,
    val agentCount: Int,
)
