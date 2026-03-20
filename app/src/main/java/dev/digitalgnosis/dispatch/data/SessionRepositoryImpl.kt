package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [SessionRepository].
 *
 * Fetches real-time session captures from the File Bridge SQLite pipeline.
 * Injected by Hilt via [SessionRepository] interface — never inject this class directly.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val client: BaseFileBridgeClient
) : SessionRepository {

    override fun fetchSessions(
        dept: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): SessionRepository.SessionListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (dept != null) append("&dept=$dept")
            if (status != null) append("&status=$status")
        }
        Timber.d("SessionRepo: fetchSessions — requesting (dept=%s, status=%s, limit=%d, offset=%d)", dept, status, limit, offset)
        val body = client.get("sessions/?$params") ?: return SessionRepository.SessionListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            val result = SessionRepository.SessionListResult(sessions, json.optInt("total", sessions.size))
            Timber.d("SessionRepo: fetchSessions — got %d sessions (total=%d)", result.sessions.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: fetchSessions parse failed")
            SessionRepository.SessionListResult(emptyList(), 0)
        }
    }

    override fun fetchForDispatch(dept: String?, limit: Int): SessionRepository.SessionListResult {
        val params = buildString {
            append("limit=$limit")
            if (dept != null) append("&dept=$dept")
        }
        Timber.d("SessionRepo: fetchForDispatch — requesting (dept=%s, limit=%d)", dept, limit)
        val body = client.get("sessions/for-dispatch?$params")
            ?: return SessionRepository.SessionListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            val result = SessionRepository.SessionListResult(sessions, json.optInt("total", sessions.size))
            Timber.d("SessionRepo: fetchForDispatch — got %d sessions (total=%d)", result.sessions.size, result.total)
            result
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: fetchForDispatch parse failed")
            SessionRepository.SessionListResult(emptyList(), 0)
        }
    }

    override fun fetchActiveSessions(dept: String?): SessionRepository.SessionListResult {
        val params = if (dept != null) "?dept=$dept" else ""
        Timber.d("SessionRepo: fetchActiveSessions — requesting (dept=%s)", dept)
        val body = client.get("sessions/active$params") ?: return SessionRepository.SessionListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("sessions")
            val sessions = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                sessions.add(parseSessionInfo(arr.getJSONObject(i)))
            }
            val result = SessionRepository.SessionListResult(sessions, json.optInt("total", sessions.size))
            Timber.d("SessionRepo: fetchActiveSessions — got %d active sessions", result.sessions.size)
            result
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: fetchActiveSessions parse failed")
            SessionRepository.SessionListResult(emptyList(), 0)
        }
    }

    override fun fetchChatBubbles(
        sessionId: String,
        limit: Int,
        tail: Boolean,
        beforeSequence: Int,
        sinceSequence: Int,
    ): ChatBubblesResult {
        val params = buildString {
            append("limit=$limit&tail=$tail")
            if (beforeSequence > 0) append("&before_sequence=$beforeSequence")
            if (sinceSequence > 0) append("&since_sequence=$sinceSequence")
        }
        Timber.d("SessionRepo: fetchChatBubbles — requesting (session=%s, limit=%d, tail=%b, before=%d, since=%d)",
            sessionId.take(8), limit, tail, beforeSequence, sinceSequence)
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
            val result = ChatBubblesResult(
                bubbles = bubbles,
                sessionId = json.optString("session_id", sessionId),
                maxSequence = json.optInt("max_sequence", 0),
                minSequence = json.optInt("min_sequence", 0),
                hasMore = json.optBoolean("has_more", false),
                hasEarlier = json.optBoolean("has_earlier", false),
            )
            Timber.d("SessionRepo: fetchChatBubbles — got %d bubbles for %s (min=%d, max=%d, hasEarlier=%b)",
                result.bubbles.size, sessionId.take(8), result.minSequence, result.maxSequence, result.hasEarlier)
            result
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: fetchChatBubbles parse failed for %s", sessionId.take(8))
            ChatBubblesResult(emptyList(), sessionId, 0, 0, false, false)
        }
    }

    override fun fetchSessionDetail(
        sessionId: String,
        sinceSequence: Int,
        limit: Int,
    ): SessionDetail? {
        val params = "since_sequence=$sinceSequence&limit=$limit"
        Timber.d("SessionRepo: fetchSessionDetail — requesting (session=%s, since=%d, limit=%d)",
            sessionId.take(8), sinceSequence, limit)
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

            val detail = SessionDetail(
                session = parseSessionInfo(sessionJson),
                records = records,
                totalRecords = json.optInt("total_records", 0),
                maxSequence = json.optInt("max_sequence", 0),
                hasMore = json.optBoolean("has_more", false),
            )
            Timber.d("SessionRepo: fetchSessionDetail — got %d records for %s (maxSeq=%d, status=%s)",
                detail.records.size, sessionId.take(8), detail.maxSequence, detail.session.status)
            detail
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: fetchSessionDetail parse failed for %s", sessionId.take(8))
            null
        }
    }

    override fun sendSessionCommand(sessionId: String, command: String): Result<String> {
        Timber.d("SessionRepo: sendSessionCommand — requesting (session=%s, command=%s)", sessionId.take(8), command)
        val payload = JSONObject().apply {
            put("command", command)
        }

        val body = client.post("sessions/$sessionId/command", payload.toString())
            ?: return Result.failure(Exception("Network error"))

        return try {
            val json = JSONObject(body)
            if (json.optString("status") == "ok") {
                val output = json.optString("output", "OK")
                Timber.d("SessionRepo: sendSessionCommand — success (output=%s)", output.take(80))
                Result.success(output)
            } else {
                val detail = json.optString("detail", "Unknown error")
                Timber.w("SessionRepo: sendSessionCommand — server error: %s", detail)
                Result.failure(Exception(detail))
            }
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: sendSessionCommand failed for %s", sessionId.take(8))
            Result.failure(e)
        }
    }

    override fun sendSlashCommand(command: String, department: String): Result<String> {
        Timber.d("SessionRepo: sendSlashCommand — requesting (command=%s, dept=%s)", command, department)
        val payload = JSONObject().apply {
            put("command", command)
            put("target", department)
        }

        val body = client.post("command", payload.toString())
            ?: return Result.failure(Exception("Network error"))

        return try {
            val json = JSONObject(body)
            if (json.optString("status") == "ok") {
                val output = json.optString("output", "OK")
                Timber.d("SessionRepo: sendSlashCommand — success (output=%s)", output.take(80))
                Result.success(output)
            } else {
                val detail = json.optString("detail", "Unknown error")
                Timber.w("SessionRepo: sendSlashCommand — server error: %s", detail)
                Result.failure(Exception(detail))
            }
        } catch (e: Exception) {
            Timber.e(e, "SessionRepo: sendSlashCommand failed (command=%s, dept=%s)", command, department)
            Result.failure(e)
        }
    }

    override fun streamChat(
        message: String,
        department: String?,
        model: String?,
    ): Flow<StreamEvent> = callbackFlow {
        val payload = JSONObject().apply {
            put("message", message)
            if (department != null) put("department", department)
            if (model != null) put("model", model)
        }

        val url = client.buildUrl("chat/stream")
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        val factory = EventSources.createFactory(client.getStreamingClient())
        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Timber.i("StreamChat: SSE connected (status=%d)", response.code)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                try {
                    val json = JSONObject(data)
                    val eventType = type ?: json.optString("type", "")

                    val event: StreamEvent? = when (eventType) {
                        "token", "content_block_delta" -> {
                            val text = json.optString("text", "")
                                .ifBlank { json.optJSONObject("delta")?.optString("text", "") ?: "" }
                            if (text.isNotEmpty()) StreamEvent.Token(text) else null
                        }
                        "thinking" -> StreamEvent.Thinking(json.optString("thinking", ""))
                        "tool_use", "tool" -> StreamEvent.Tool(
                            name = json.optString("name", json.optString("tool_name", "")),
                            status = json.optString("status", "started"),
                        )
                        "assistant" -> {
                            val text = json.optString("text", "")
                            if (text.isNotBlank()) StreamEvent.AssistantText(text) else null
                        }
                        "done", "result" -> StreamEvent.Done(
                            result = json.optString("result", ""),
                            costUsd = json.optDouble("total_cost_usd", 0.0),
                            durationMs = json.optLong("duration_ms", 0),
                            sessionId = json.optString("session_id", ""),
                            stopReason = json.optString("stop_reason", ""),
                        )
                        "sentence" -> {
                            val text = json.optString("text", "")
                            if (text.isNotEmpty()) StreamEvent.Sentence(text) else null
                        }
                        "error" -> StreamEvent.Error(
                            errorType = json.optString("error_type", "stream_error"),
                            result = json.optString("message", json.optString("result", "")),
                        )
                        "status" -> StreamEvent.Status(
                            status = json.optString("status", ""),
                            model = json.optString("model", ""),
                            sessionId = json.optString("session_id", ""),
                        )
                        else -> null
                    }

                    if (event != null) trySend(event)
                } catch (e: Exception) {
                    Timber.w(e, "StreamChat: failed to parse event type=%s", type)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Timber.i("StreamChat: SSE closed")
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code ?: -1
                Timber.e(t, "StreamChat: SSE failed (code=%d)", code)
                trySend(StreamEvent.Error(
                    "connection_error",
                    t?.message ?: "SSE connection failed (code=$code)"
                ))
                channel.close()
            }
        })

        awaitClose {
            Timber.i("StreamChat: Flow cancelled, closing SSE")
            eventSource.cancel()
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
