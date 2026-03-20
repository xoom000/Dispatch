package dev.digitalgnosis.dispatch.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction
import androidx.appfunctions.service.AppFunctionStringValueConstraint
import dev.digitalgnosis.dispatch.data.CmailRepository
import dev.digitalgnosis.dispatch.data.SessionRepository
import dev.digitalgnosis.dispatch.data.VoiceNotificationRepository
import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Dispatch AppFunctions — exposes Dispatch capabilities to on-device AI agents (Gemini, etc.)
 * via the Android 16 AppFunctions platform.
 *
 * Each function here is discoverable and invocable by any agent that holds
 * EXECUTE_APP_FUNCTIONS permission. The platform enforces that permission at the
 * system level before binding to [dev.digitalgnosis.dispatch.appfunctions.DispatchAppFunctionService].
 *
 * All functions are suspend and dispatch blocking I/O to [Dispatchers.IO].
 *
 * Wired in: [DispatchApplication.appFunctionConfiguration] via [AppFunctionEntryPoint].
 */
class DispatchAppFunctions(
    private val cmailRepository: CmailRepository,
    private val sessionRepository: SessionRepository,
    private val voiceNotificationRepository: VoiceNotificationRepository,
    private val fileBridgeClient: BaseFileBridgeClient,
) {

    // ── sendMessage ───────────────────────────────────────────────────────────

    /**
     * Send a message to one or more Dispatch departments via cmail.
     *
     * Use this to relay a user's message to a department agent. Each department receives
     * the message independently. Set invokeAgent to true to also trigger an AI agent
     * session in the target department immediately after sending.
     *
     * @param appFunctionContext Execution context provided by the platform.
     * @param request The message content and routing parameters.
     */
    @AppFunction(isEnabled = true, isDescribedByKDoc = true)
    suspend fun sendMessage(
        appFunctionContext: AppFunctionContext,
        request: SendMessageRequest,
    ): SendMessageResponse {
        if (request.message.isBlank()) {
            return SendMessageResponse(success = false, threadId = null, error = "message must not be blank")
        }
        val departments = request.departments
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (departments.isEmpty()) {
            return SendMessageResponse(success = false, threadId = null, error = "departments must not be empty")
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = if (departments.size == 1) {
                    cmailRepository.sendCmail(
                        department = departments.first(),
                        message = request.message,
                        invoke = request.invokeAgent,
                    )
                } else {
                    cmailRepository.sendCmailGroup(
                        departments = departments,
                        message = request.message,
                        invoke = request.invokeAgent,
                    )
                }
                if (result.isSuccess) {
                    val sent = result.getOrThrow()
                    Timber.i("AppFunctions.sendMessage: sent to %s, thread=%s", departments, sent.threadId)
                    SendMessageResponse(success = true, threadId = sent.threadId)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "send failed"
                    Timber.w("AppFunctions.sendMessage: failed — %s", msg)
                    SendMessageResponse(success = false, threadId = null, error = msg)
                }
            } catch (e: Exception) {
                Timber.e(e, "AppFunctions.sendMessage: unexpected error")
                SendMessageResponse(success = false, threadId = null, error = e.message ?: "unexpected error")
            }
        }
    }

    // ── checkInbox ────────────────────────────────────────────────────────────

    /**
     * Return recent incoming voice dispatch messages from the user's inbox.
     *
     * Messages are returned newest first. Use this to check what the most recent
     * dispatches were, who sent them, and whether they have file attachments.
     *
     * @param appFunctionContext Execution context provided by the platform.
     * @param request Filter and limit parameters for the inbox query.
     */
    @AppFunction(isEnabled = true, isDescribedByKDoc = true)
    suspend fun checkInbox(
        appFunctionContext: AppFunctionContext,
        request: GetDispatchHistoryRequest,
    ): GetDispatchHistoryResponse {
        return withContext(Dispatchers.IO) {
            try {
                val raw = fileBridgeClient.get("/dispatch/history?limit=${request.limit}")
                    ?: return@withContext GetDispatchHistoryResponse(
                        items = emptyList(),
                        error = "File Bridge returned no data",
                    )

                val items = parseHistoryJson(raw, request.senderFilter, request.limit)
                Timber.i("AppFunctions.checkInbox: returned %d items", items.size)
                GetDispatchHistoryResponse(items = items)
            } catch (e: Exception) {
                Timber.e(e, "AppFunctions.checkInbox: error")
                GetDispatchHistoryResponse(items = emptyList(), error = e.message ?: "error reading inbox")
            }
        }
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    /**
     * List active Claude agent sessions, optionally filtered by department.
     *
     * Returns session summaries including department, current status, and last activity
     * time. Use this to check which agents are currently running and when they last did work.
     *
     * @param appFunctionContext Execution context provided by the platform.
     * @param request Department filter and result limit.
     */
    @AppFunction(isEnabled = true, isDescribedByKDoc = true)
    suspend fun getStatus(
        appFunctionContext: AppFunctionContext,
        request: GetSessionsRequest,
    ): GetSessionsResponse {
        return withContext(Dispatchers.IO) {
            try {
                val dept = request.department.takeIf { it.isNotBlank() }
                val result = sessionRepository.fetchActiveSessions(dept = dept)
                val sessions = result.sessions
                    .take(request.limit)
                    .map { info ->
                        SessionSummary(
                            sessionId = info.sessionId,
                            department = info.department,
                            summary = info.summary ?: "",
                            status = info.status,
                            lastActivity = info.lastActivity,
                            isActive = info.isActive,
                        )
                    }
                Timber.i("AppFunctions.getStatus: %d active sessions (dept=%s)", sessions.size, dept ?: "all")
                GetSessionsResponse(sessions = sessions)
            } catch (e: Exception) {
                Timber.e(e, "AppFunctions.getStatus: error")
                GetSessionsResponse(sessions = emptyList(), error = e.message ?: "error fetching sessions")
            }
        }
    }

    // ── queryRoute ────────────────────────────────────────────────────────────

    /**
     * Retrieve the recent conversation bubbles for a given session.
     *
     * Returns the most recent messages from a session thread — both user messages
     * ("nigel" type) and agent responses ("agent" type). Use this to read what was
     * said in a conversation without opening the app.
     *
     * @param appFunctionContext Execution context provided by the platform.
     * @param request The session ID to fetch and how many bubbles to return.
     */
    @AppFunction(isEnabled = true, isDescribedByKDoc = true)
    suspend fun queryRoute(
        appFunctionContext: AppFunctionContext,
        request: GetConversationRequest,
    ): GetConversationResponse {
        if (request.sessionId.isBlank()) {
            return GetConversationResponse(
                sessionId = request.sessionId,
                bubbles = emptyList(),
                hasEarlier = false,
                error = "sessionId must not be blank",
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                val result = sessionRepository.fetchChatBubbles(
                    sessionId = request.sessionId,
                    limit = request.limit,
                    tail = true,
                )
                val bubbles = result.bubbles.map { b ->
                    ConversationBubble(
                        type = b.type,
                        text = b.text,
                        timestamp = b.timestamp,
                        sequence = b.sequence,
                    )
                }
                Timber.i("AppFunctions.queryRoute: %d bubbles for session %s", bubbles.size, request.sessionId)
                GetConversationResponse(
                    sessionId = request.sessionId,
                    bubbles = bubbles,
                    hasEarlier = result.hasEarlier,
                )
            } catch (e: Exception) {
                Timber.e(e, "AppFunctions.queryRoute: error for session %s", request.sessionId)
                GetConversationResponse(
                    sessionId = request.sessionId,
                    bubbles = emptyList(),
                    hasEarlier = false,
                    error = e.message ?: "error fetching conversation",
                )
            }
        }
    }

    // ── checkDeliveryGaps ─────────────────────────────────────────────────────

    /**
     * Check dispatch message history for delivery gaps or failures.
     *
     * Returns the recent dispatch history including delivery success/failure status
     * for each message. Failed entries indicate delivery gaps — messages the system
     * attempted to send but could not deliver. Use this for operational diagnostics.
     *
     * @param appFunctionContext Execution context provided by the platform.
     * @param request How many history entries to return and an optional sender filter.
     */
    @AppFunction(isEnabled = true, isDescribedByKDoc = true)
    suspend fun checkDeliveryGaps(
        appFunctionContext: AppFunctionContext,
        request: GetDispatchHistoryRequest,
    ): GetDispatchHistoryResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append("/dispatch/history?limit=")
                    append(request.limit)
                    if (request.senderFilter.isNotBlank()) {
                        append("&sender=")
                        append(request.senderFilter)
                    }
                }
                val raw = fileBridgeClient.get(url)
                    ?: return@withContext GetDispatchHistoryResponse(
                        items = emptyList(),
                        error = "File Bridge returned no data",
                    )

                // Return all items but flag failures — callers inspect success=false entries
                val items = parseHistoryJson(raw, request.senderFilter, request.limit)
                val failCount = items.count { !it.success }
                Timber.i("AppFunctions.checkDeliveryGaps: %d items, %d failures", items.size, failCount)
                GetDispatchHistoryResponse(items = items)
            } catch (e: Exception) {
                Timber.e(e, "AppFunctions.checkDeliveryGaps: error")
                GetDispatchHistoryResponse(items = emptyList(), error = e.message ?: "error reading history")
            }
        }
    }

    // ── Shared parsing ────────────────────────────────────────────────────────

    /**
     * Parse the JSON response from GET /dispatch/history into [DispatchHistoryItem] list.
     * Applies sender filter client-side when the server doesn't support the query param.
     */
    private fun parseHistoryJson(
        raw: String,
        senderFilter: String,
        limit: Int,
    ): List<DispatchHistoryItem> {
        val arr: JSONArray = try {
            // Server may return { "history": [...] } or a bare array
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("history") ?: JSONArray()
            }
        } catch (e: Exception) {
            Timber.w(e, "AppFunctions: failed to parse history JSON")
            return emptyList()
        }

        val result = mutableListOf<DispatchHistoryItem>()
        for (i in 0 until arr.length()) {
            if (result.size >= limit) break
            val obj = arr.optJSONObject(i) ?: continue
            val sender = obj.optString("sender", "")
            if (senderFilter.isNotBlank() && !sender.equals(senderFilter, ignoreCase = true)) continue
            result += DispatchHistoryItem(
                id = obj.optInt("id", i),
                timestamp = obj.optString("timestamp", ""),
                sender = sender,
                message = obj.optString("message", ""),
                priority = obj.optString("priority", "normal"),
                success = obj.optBoolean("success", true),
                threadId = obj.optString("thread_id", "").takeIf { it.isNotBlank() },
            )
        }
        return result
    }
}
