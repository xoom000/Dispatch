package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Cmail messaging, threading, and department discovery.
 *
 * All messages flow through the File Bridge relay on pop-os.
 */
@Singleton
class CmailRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Send a cmail message through the File Bridge relay.
     */
    fun sendCmail(
        department: String,
        message: String,
        subject: String? = null,
        priority: String = "normal",
        invoke: Boolean = true,
        threadId: String? = null,
        agentType: String? = null,
    ): Result<CmailSendResult> {
        Timber.i("Cmail: prepare send to %s (agent=%s, thread=%s)", department, agentType, threadId)
        val payload = JSONObject().apply {
            put("department", department)
            put("message", message)
            if (subject != null) put("subject", subject)
            put("priority", priority)
            put("invoke", invoke)
            if (threadId != null) put("thread_id", threadId)
            if (agentType != null) put("agent_type", agentType)
        }

        Timber.d("Cmail: payload = %s", payload.toString())
        val body = client.post("cmail/send", payload.toString())
        
        if (body == null) {
            Timber.w("Cmail: send FAILED (network error or non-200 status)")
            return Result.failure(Exception("Network error"))
        }

        return try {
            val json = JSONObject(body)
            Timber.i("Cmail: response received = %s", body)
            val result = CmailSendResult(
                success = true,
                message = "Sent to $department",
                invoked = json.optBoolean("invoked", false),
                department = json.optString("department", department).ifBlank { department },
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id", "").ifBlank { null }
            )
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "Cmail: parse response FAILED")
            Result.failure(e)
        }
    }

    /**
     * Send a group cmail message to multiple departments.
     */
    fun sendCmailGroup(
        departments: List<String>,
        message: String,
        subject: String? = null,
        priority: String = "normal",
        invoke: Boolean = true,
        threadId: String? = null,
        agentType: String? = null,
    ): Result<CmailSendResult> {
        val payload = JSONObject().apply {
            put("departments", JSONArray(departments))
            put("message", message)
            if (subject != null) put("subject", subject)
            put("priority", priority)
            put("invoke", invoke)
            if (threadId != null) put("thread_id", threadId)
            if (agentType != null) put("agent_type", agentType)
        }

        val body = client.post("cmail/send", payload.toString())
            ?: return Result.failure(Exception("Network error"))

        return try {
            val json = JSONObject(body)
            val result = CmailSendResult(
                success = true,
                message = "Sent to ${departments.joinToString(", ")}",
                invoked = json.optBoolean("invoked", false),
                department = json.optString("department", departments.first()).ifBlank { departments.first() },
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id", "").ifBlank { null }
            )
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch available departments from File Bridge.
     */
    fun fetchDepartments(): List<DepartmentInfo> {
        val body = client.get("cmail/departments") ?: return emptyList()

        return try {
            val json = JSONObject(body)
            val depts = json.optJSONArray("departments") ?: JSONArray()
            val result = mutableListOf<DepartmentInfo>()

            // Add synthetic Gemini CLI contact
            result.add(DepartmentInfo(
                name = "Gemini CLI",
                description = "Digital Gnosis Project Architect",
                voice = "am_michael",
            ))

            for (i in 0 until depts.length()) {
                val dept = depts.getJSONObject(i)
                if (dept.optBoolean("has_agent", false)) {
                    result.add(DepartmentInfo(
                        name = dept.getString("name"),
                        description = dept.optString("description", ""),
                        voice = dept.optString("voice", "am_michael"),
                    ))
                }
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "CmailDepts: parse failed")
            emptyList()
        }
    }

    /**
     * Fetch thread list from File Bridge. Newest activity first.
     */
    fun fetchThreads(
        limit: Int = 30,
        offset: Int = 0,
        participant: String? = null,
    ): ThreadListResult {
        val params = buildString {
            append("limit=$limit&offset=$offset")
            if (participant != null) append("&participant=$participant")
        }
        val body = client.get("cmail/threads?$params") ?: return ThreadListResult(emptyList(), 0)

        return try {
            val json = JSONObject(body)
            val arr = json.getJSONArray("threads")
            val threads = mutableListOf<ThreadInfo>()

            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                threads.add(ThreadInfo(
                    threadId = t.getString("thread_id"),
                    subject = t.optString("subject", ""),
                    participants = parseStringArray(t.optJSONArray("participants")),
                    messageCount = t.optInt("total_messages", t.optInt("message_count", 0)),
                    createdAt = t.optString("created_at", ""),
                    lastActivity = t.optString("last_activity", ""),
                    lastSender = if (t.isNull("last_sender")) null else t.optString("last_sender", ""),
                    lastMessagePreview = if (t.isNull("last_message_preview")) null else t.optString("last_message_preview", ""),
                ))
            }
            ThreadListResult(threads, json.optInt("total", threads.size))
        } catch (e: Exception) {
            Timber.e(e, "Threads: parse failed")
            ThreadListResult(emptyList(), 0)
        }
    }

    /**
     * Reply to an existing thread.
     */
    fun replyToThread(
        threadId: String,
        department: String,
        message: String,
        invoke: Boolean = true,
        agentType: String? = null,
    ): Result<CmailSendResult> {
        return sendCmail(
            department = department,
            message = message,
            threadId = threadId,
            invoke = invoke,
            agentType = agentType
        )
    }

    /**
     * Fetch thread detail with all messages.
     * C3 Fix: Client-side deduplication using synthetic IDs and distinctBy.
     */
    fun fetchThreadDetail(threadId: String): ThreadDetail? {
        val body = client.get("cmail/threads/$threadId") ?: return null

        return try {
            val json = JSONObject(body)
            val msgArr = json.getJSONArray("messages")
            val messages = mutableListOf<ThreadMessage>()

            for (i in 0 until msgArr.length()) {
                val m = msgArr.getJSONObject(i)
                val rawId = m.optString("message_id", "")
                val sender = m.optString("sender", "unknown")
                val pos = m.optInt("position", i)
                
                // C3 Fix: If server provides blank ID, build a stable synthetic one
                val finalId = if (rawId.isBlank()) "gen_${sender}_${pos}_${threadId.take(8)}" else rawId
                
                messages.add(ThreadMessage(
                    messageId = finalId,
                    threadId = m.optString("thread_id", threadId),
                    position = pos,
                    sender = sender,
                    recipient = m.optString("recipient", ""),
                    cc = if (m.isNull("cc")) null else m.optString("cc", ""),
                    subject = m.optString("subject", ""),
                    body = m.optString("body", ""),
                    priority = m.optString("priority", "normal"),
                    delivery = m.optString("delivery", "direct"),
                    read = m.optInt("read", 0) == 1,
                    createdAt = m.optString("created_at", ""),
                    attachments = if (m.isNull("attachments")) null else m.optString("attachments", "")
                ))
            }

            // C3 Fix: Ensure uniqueness by messageId
            val dedupedMessages = messages.distinctBy { it.messageId }

            ThreadDetail(
                threadId = json.optString("thread_id", threadId),
                subject = json.optString("subject", ""),
                participants = parseStringArray(json.optJSONArray("participants")),
                messageCount = json.optInt("message_count", dedupedMessages.size),
                createdAt = json.optString("created_at", ""),
                lastActivity = json.optString("last_activity", ""),
                messages = dedupedMessages
            )
        } catch (e: Exception) {
            Timber.e(e, "ThreadDetail: parse failed for $threadId")
            null
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            result.add(arr.getString(i))
        }
        return result
    }

    /**
     * Data wrapper for thread list responses.
     */
    data class ThreadListResult(
        val threads: List<ThreadInfo>,
        val total: Int,
    )
}

data class CmailSendResult(
    val success: Boolean,
    val message: String,
    val invoked: Boolean = false,
    val department: String? = null,
    val sessionId: String? = null,
)

data class DepartmentInfo(
    val name: String,
    val description: String,
    val voice: String = "am_michael",
)
