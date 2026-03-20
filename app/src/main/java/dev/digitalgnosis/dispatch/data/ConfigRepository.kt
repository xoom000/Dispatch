package dev.digitalgnosis.dispatch.data

/**
 * Domain interface for system configuration, voice maps, and connectivity status.
 */
interface ConfigRepository {

    fun fetchVoiceMap(): VoiceMapResult

    fun updateVoiceAssignment(department: String, voice: String): Boolean

    fun testConnection(): String
}
