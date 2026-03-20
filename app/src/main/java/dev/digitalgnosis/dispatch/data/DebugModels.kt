package dev.digitalgnosis.dispatch.data

/**
 * A single entry from a server-side log file.
 */
data class ServerLogEntry(
    val timestamp: String,
    val tag: String,
    val text: String,
    val raw: String,
)

/**
 * Result of fetching a log file tail from the File Bridge.
 */
data class LogTailResult(
    val name: String,
    val exists: Boolean,
    val entries: List<ServerLogEntry>,
    val sizeBytes: Long,
)

/**
 * Metadata for a single log file on the File Bridge server.
 */
data class LogFileMeta(
    val name: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val modified: Double,
)

/**
 * Summary of an active Claude Code session as seen from the debug view.
 */
data class ActiveSessionInfo(
    val sessionId: String,
    val department: String,
    val summary: String,
    val model: String,
    val lastActivity: String,
    val recordCount: Int,
    val gitBranch: String,
    val contextPct: Double,
)

/**
 * Server health response from GET /health.
 */
data class ServerHealth(
    val status: String,
    val version: String,
    val stagedFiles: Int,
)
