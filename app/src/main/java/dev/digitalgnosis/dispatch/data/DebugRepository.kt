package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for server-side debug log data.
 *
 * Fetches log tails from File Bridge /logs/ endpoints.
 */
@Singleton
class DebugRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch last N lines from a named log file.
     */
    fun fetchLogTail(logName: String, lines: Int = 200): LogTailResult {
        Timber.d("DebugRepo: fetchLogTail — requesting (log=%s, lines=%d)", logName, lines)
        val body = client.get("logs/$logName?lines=$lines")
            ?: return LogTailResult(logName, exists = false, entries = emptyList(), sizeBytes = 0)

        return try {
            val json = JSONObject(body)
            val exists = json.optBoolean("exists", false)
            val sizeBytes = json.optLong("size_bytes", 0)
            val linesArr = json.optJSONArray("lines")

            val entries = mutableListOf<ServerLogEntry>()
            if (linesArr != null) {
                for (i in 0 until linesArr.length()) {
                    val line = linesArr.getJSONObject(i)
                    entries.add(
                        ServerLogEntry(
                            timestamp = line.optString("timestamp", ""),
                            tag = line.optString("tag", ""),
                            text = line.optString("text", ""),
                            raw = line.optString("raw", ""),
                        )
                    )
                }
            }

            val result = LogTailResult(logName, exists, entries, sizeBytes)
            Timber.d("DebugRepo: fetchLogTail — got %d entries from %s (exists=%b, size=%d)",
                result.entries.size, logName, result.exists, result.sizeBytes)
            result
        } catch (e: Exception) {
            Timber.e(e, "DebugRepo: fetchLogTail parse failed for %s", logName)
            LogTailResult(logName, exists = false, entries = emptyList(), sizeBytes = 0)
        }
    }

    /**
     * Fetch metadata for all available log files.
     */
    fun fetchLogIndex(): List<LogFileMeta> {
        Timber.d("DebugRepo: fetchLogIndex — requesting")
        val body = client.get("logs/") ?: return emptyList()

        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("logs") ?: return emptyList()
            val result = mutableListOf<LogFileMeta>()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                result.add(
                    LogFileMeta(
                        name = f.optString("name", ""),
                        exists = f.optBoolean("exists", false),
                        sizeBytes = f.optLong("size_bytes", 0),
                        modified = f.optDouble("modified", 0.0),
                    )
                )
            }
            Timber.d("DebugRepo: fetchLogIndex — got %d log files", result.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "DebugRepo: fetchLogIndex parse failed")
            emptyList()
        }
    }

    /**
     * Fetch active sessions from File Bridge.
     */
    fun fetchActiveSessions(): List<ActiveSessionInfo> {
        Timber.d("DebugRepo: fetchActiveSessions — requesting")
        val body = client.get("sessions/active") ?: return emptyList()

        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("sessions") ?: return emptyList()
            val result = mutableListOf<ActiveSessionInfo>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                result.add(
                    ActiveSessionInfo(
                        sessionId = s.optString("session_id", ""),
                        department = s.optString("department", ""),
                        summary = s.optString("summary", ""),
                        model = s.optString("model", ""),
                        lastActivity = s.optString("last_activity", ""),
                        recordCount = s.optInt("record_count", 0),
                        gitBranch = s.optString("git_branch", ""),
                        contextPct = s.optDouble("context_pct", 0.0),
                    )
                )
            }
            Timber.d("DebugRepo: fetchActiveSessions — got %d active sessions", result.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "DebugRepo: fetchActiveSessions parse failed")
            emptyList()
        }
    }

    /**
     * Fetch server health info.
     */
    fun fetchHealth(): ServerHealth? {
        Timber.d("DebugRepo: fetchHealth — requesting")
        val body = client.get("health") ?: return null

        return try {
            val json = JSONObject(body)
            val health = ServerHealth(
                status = json.optString("status", "unknown"),
                version = json.optString("version", ""),
                stagedFiles = json.optInt("staged_files", 0),
            )
            Timber.d("DebugRepo: fetchHealth — status=%s, version=%s, staged=%d",
                health.status, health.version, health.stagedFiles)
            health
        } catch (e: Exception) {
            Timber.e(e, "DebugRepo: fetchHealth parse failed")
            null
        }
    }
}

// ── Data classes ────────────────────────────────────────────────────

data class ServerLogEntry(
    val timestamp: String,
    val tag: String,
    val text: String,
    val raw: String,
)

data class LogTailResult(
    val name: String,
    val exists: Boolean,
    val entries: List<ServerLogEntry>,
    val sizeBytes: Long,
)

data class LogFileMeta(
    val name: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val modified: Double,
)

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

data class ServerHealth(
    val status: String,
    val version: String,
    val stagedFiles: Int,
)
