package dev.digitalgnosis.dispatch.data

import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for the Gemini Native path.
 *
 * Communicates with dedicated /gemini endpoints on the File Bridge, bypassing the
 * Cmail/Claude path for high-fidelity control.
 */
interface GeminiRepository {

    fun sendNativePrompt(threadId: String, message: String): Flow<GeminiUpdate>

    suspend fun fetchSessions(): List<GeminiSessionInfo>

    suspend fun fetchSessionDetail(sessionId: String): org.json.JSONObject?
}

data class GeminiSessionInfo(
    val id: String,
    val startTime: String,
    val lastUpdated: String,
    val messageCount: Int,
    val preview: String,
)

sealed class GeminiUpdate {
    data class Thought(val text: String) : GeminiUpdate()
    data class MessageChunk(val text: String) : GeminiUpdate()
    data class Error(val message: String) : GeminiUpdate()
}
