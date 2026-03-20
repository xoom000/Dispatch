package dev.digitalgnosis.dispatch.ui

/**
 * Mutable state holder for the compose/send screen.
 *
 * Lives in MainActivity and is passed down to SendScreen so draft state
 * survives tab switches without being tied to the ViewModel lifecycle.
 * Mirrors the original APK implementation from MainActivity.kt.
 */
class SendDraft {
    var messageText: String = ""
    var selectedDepts: Set<String> = emptySet()
    var invokeAgent: Boolean = true
    var selectedThreadId: String? = null
    var attachedFiles: List<DraftFile> = emptyList()

    fun clearDraft() {
        messageText = ""
        attachedFiles = emptyList()
        selectedThreadId = null
    }

    data class DraftFile(
        val name: String,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DraftFile) return false
            return name == other.name && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
    }
}
