package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for server-side debug log data.
 */
interface DebugRepository {

    fun fetchLogTail(logName: String, lines: Int = 200): LogTailResult

    fun fetchLogIndex(): List<LogFileMeta>

    fun fetchActiveSessions(): List<ActiveSessionInfo>

    fun fetchHealth(): ServerHealth?
}
