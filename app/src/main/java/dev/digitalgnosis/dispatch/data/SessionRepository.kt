package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Claude Code session data and agent interactions.
 *
 * Fetches real-time session captures from the File Bridge SQLite pipeline.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch session list from File Bridge. Newest activity first.
     */
    fun fetchSessions(
        dept: String? = null,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SessionListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (dept != null) append("&dept=$dept")
            if (status != null) append("&status=$status")
        }
        
        val body = client.get("sessions/?$params") ?: return SessionListResult(emptyList(), 0)
        
        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            SessionListResult(sessions, json.optInt("total", sessions.size))
        } catch (e: Exception) {
            Timber.e(e, "Sessions: parse failed")
            SessionListResult(emptyList(), 0)
        }
    }

    /**
     * Fetch sessions optimized for Dispatch Chat tab.
     * Returns sessions enriched with stable aliases (eng-3, pg-1).
     */
    fun fetchForDispatch(dept: String? = null, limit: Int = 30): SessionListResult {
        val params = buildString {
            append("limit=$limit")
            if (dept != null) append("&dept=$dept")
        }

        val body = client.get("sessions/for-dispatch?$params")
            ?: return SessionListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            SessionListResult(sessions, json.optInt("total", sessions.size))
        } catch (e: Exception) {
            Timber.e(e, "ForDispatch: parse failed")
            SessionListResult(emptyList(), 0)
        }
    }

    /**
     * Fetch active sessions from File Bridge.
     */
    fun fetchActiveSessions(dept: String? = null): SessionListResult {
        val params = if (dept != null) "?dept=$dept" else ""
        val body = client.get("sessions/active$params") ?: return SessionListResult(emptyList(), 0)
        
        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            SessionListResult(sessions, json.optInt("total", sessions.size))
        } catch (e: Exception) {
            Timber.e(e, "ActiveSessions: parse failed")
            SessionListResult(emptyList(), 0)
        }
    }

    /**
     * Data wrapper for session list responses.
     */
    data class SessionListResult(
        val sessions: List<SessionInfo>,
        val total: Int,
    )

    /**
     * Fetch chat bubbles for a session — pre-classified by the server.
     * Returns 4 bubble types: nigel, agent, dispatch, tool.
     *
     * Default (tail=true): returns the LAST `limit` bubbles (most recent).
     * For scroll-up lazy loading: pass beforeSequence to get older messages.
     */
    fun fetchChatBubbles(
        sessionId: String,
        limit: Int = 100,
        tail: Boolean = true,
        beforeSequence: Int = 0,
        sinceSequence: Int = 0,
    ): ChatBubblesResult {
        val params = buildString {
            append("limit=$limit&tail=$tail")
            if (beforeSequence > 0) append("&before_sequence=$beforeSequence")
            if (sinceSequence > 0) append("&since_sequence=$sinceSequence")
        }
        val body = client.get("sessions/$sessionId/chat?$params")
            ?: return ChatBubblesResult(emptyList(), sessionId, 0, 0, false, false)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("bubbles")
            val bubbles = mutableListOf<ChatBubble>()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bubbles.add(ChatBubble(
                    type = b.optString("type", "agent"),
                    text = b.optString("text", ""),
                    detail = b.optString("detail", ""),
                    sequence = b.optInt("sequence", 0),
                    subSeq = b.optInt("sub_seq", 0),
                    timestamp = b.optString("timestamp", ""),
                ))
            }
            ChatBubblesResult(
                bubbles = bubbles,
                sessionId = json.optString("session_id", sessionId),
                maxSequence = json.optInt("max_sequence", 0),
                minSequence = json.optInt("min_sequence", 0),
                hasMore = json.optBoolean("has_more", false),
                hasEarlier = json.optBoolean("has_earlier", false),
            )
        } catch (e: Exception) {
            Timber.e(e, "ChatBubbles: parse failed for $sessionId")
            ChatBubblesResult(emptyList(), sessionId, 0, 0, false, false)
        }
    }

    /**
     * Fetch session detail with records.
     * Use sinceSequence for incremental fetch (Live Mode).
     */
    fun fetchSessionDetail(
        sessionId: String,
        sinceSequence: Int = 0,
        limit: Int = 500,
    ): SessionDetail? {
        val params = "since_sequence=$sinceSequence&limit=$limit"
        val body = client.get("sessions/$sessionId?$params") ?: return null
        
        return try {
            val json = JSONObject(body)
            val sessionJson = json.getJSONObject("session")
            val recordsArr = json.getJSONArray("records")

            val records = mutableListOf<SessionRecord>()
            for (i in 0 until recordsArr.length()) {
                val r = recordsArr.getJSONObject(i)
                records.add(SessionRecord(
                    sequence = r.optInt("sequence", 0),
                    agentId = r.optString("agent_id", "main"),
                    recordType = r.optString("record_type", ""),
                    timestamp = r.optString("timestamp", ""),
                    model = if (r.isNull("model")) null else r.optString("model", ""),
                    contentText = if (r.isNull("content_text")) null else r.optString("content_text", ""),
                    toolName = if (r.isNull("tool_name")) null else r.optString("tool_name", ""),
                    toolInput = if (r.isNull("tool_input")) null else r.optString("tool_input", ""),
                    toolStatus = if (r.isNull("tool_status")) null else r.optString("tool_status", ""),
                    isError = r.optInt("is_error", 0) == 1,
                    tokensIn = r.optInt("tokens_in", 0),
                    tokensOut = r.optInt("tokens_out", 0),
                ))
            }

            SessionDetail(
                session = parseSessionInfo(sessionJson),
                records = records,
                totalRecords = json.optInt("total_records", 0),
                maxSequence = json.optInt("max_sequence", 0),
                hasMore = json.optBoolean("has_more", false),
            )
        } catch (e: Exception) {
            Timber.e(e, "SessionDetail: parse failed for $sessionId")
            null
        }
    }

    /**
     * Send a command to a session via File Bridge.
     * Currently supported: "compact" (compacts the session's context window).
     */
    fun sendSessionCommand(sessionId: String, command: String): Result<String> {
        val payload = JSONObject().apply {
            put("command", command)
        }
        
        val body = client.post("sessions/$sessionId/command", payload.toString())
            ?: return Result.failure(Exception("Network error"))
            
        return try {
            val json = JSONObject(body)
            if (json.optString("status") == "ok") {
                Result.success(json.optString("output", "OK"))
            } else {
                Result.failure(Exception(json.optString("detail", "Unknown error")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Execute a slash command (/compact, /cost, /context) against a department.
     */
    fun sendSlashCommand(command: String, department: String): Result<String> {
        val payload = JSONObject().apply {
            put("command", command)
            put("target", department)
        }
        
        val body = client.post("command", payload.toString())
            ?: return Result.failure(Exception("Network error"))
            
        return try {
            val json = JSONObject(body)
            if (json.optString("status") == "ok") {
                Result.success(json.optString("output", "OK"))
            } else {
                Result.failure(Exception(json.optString("detail", "Unknown error")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send a message to a department via File Bridge's cmail relay.
     * Uses the /cmail/send endpoint which shells out to cmail CLI as nigel.
     */
    fun sendCmailRelay(
        department: String,
        message: String,
        invoke: Boolean = true,
        sessionId: String? = null,
    ): Boolean {
        val payload = JSONObject().apply {
            put("department", department)
            put("message", message)
            put("invoke", invoke)
            if (sessionId != null) put("session_id", sessionId)
        }

        val body = client.post("cmail/send", payload.toString()) ?: return false

        return try {
            val json = JSONObject(body)
            json.optString("status") == "ok"
        } catch (e: Exception) {
            Timber.e(e, "sendCmailRelay: failed for %s", department)
            false
        }
    }

    private fun parseSessionInfo(s: JSONObject): SessionInfo {
        return SessionInfo(
            sessionId = s.getString("session_id"),
            department = s.optString("department", "Unknown"),
            projectKey = s.optString("project_key", ""),
            summary = if (s.isNull("summary")) null else s.optString("summary", ""),
            model = if (s.isNull("model")) null else s.optString("model", ""),
            startedAt = s.optString("started_at", ""),
            lastActivity = s.optString("last_activity", ""),
            recordCount = s.optInt("record_count", 0),
            status = s.optString("status", "completed"),
            gitBranch = if (s.isNull("git_branch")) null else s.optString("git_branch", ""),
            cwd = if (s.isNull("cwd")) null else s.optString("cwd", ""),
            contextTokens = s.optInt("context_tokens", 0),
            contextPct = s.optDouble("context_pct", 0.0),
            alias = s.optString("alias", ""),
            isActive = s.optBoolean("is_active", false),
        )
    }
}
