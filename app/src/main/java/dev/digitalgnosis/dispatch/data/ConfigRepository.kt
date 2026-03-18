package dev.digitalgnosis.dispatch.data

import dev.digitalgnosis.dispatch.network.BaseFileBridgeClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for system configuration, voice maps, and connectivity status.
 */
@Singleton
class ConfigRepository @Inject constructor(
    private val client: BaseFileBridgeClient
) {

    /**
     * Fetch the centralized voice map from File Bridge.
     */
    fun fetchVoiceMap(): VoiceMapResult {
        val body = client.get("config/voice-map") ?: return VoiceMapResult()

        return try {
            val json = JSONObject(body)
            val mapJson = json.optJSONObject("voice_map")
            val voiceMap = mutableMapOf<String, String>()
            if (mapJson != null) {
                for (key in mapJson.keys()) {
                    voiceMap[key] = mapJson.getString(key)
                }
            }

            val voicesArray = json.optJSONArray("available_voices")
            val voices = mutableListOf<String>()
            if (voicesArray != null) {
                for (i in 0 until voicesArray.length()) {
                    voices.add(voicesArray.getString(i))
                }
            }

            VoiceMapResult(voiceMap, voices)
        } catch (e: Exception) {
            Timber.e(e, "VoiceMap: parse failed")
            VoiceMapResult()
        }
    }

    /**
     * Update a single department's voice assignment on File Bridge.
     */
    fun updateVoiceAssignment(department: String, voice: String): Boolean {
        val payload = JSONObject().apply {
            put("department", department)
            put("voice", voice)
        }

        val body = client.put("config/voice-map", payload.toString())
        return body != null
    }

    /**
     * Test connectivity to the File Bridge server.
     */
    fun testConnection(): String {
        val start = System.currentTimeMillis()
        val body = client.get("health")
        val elapsed = System.currentTimeMillis() - start
        
        return if (body != null) {
            "OK: File Bridge reachable (${elapsed}ms)"
        } else {
            "FAIL: File Bridge unreachable (${elapsed}ms)"
        }
    }
}

data class VoiceMapResult(
    val voiceMap: Map<String, String> = emptyMap(),
    val availableVoices: List<String> = emptyList(),
)
