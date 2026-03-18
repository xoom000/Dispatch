package dev.digitalgnosis.dispatch.accessibility

/**
 * Sealed class representing remote phone actions that can be triggered
 * via FCM and executed through the AccessibilityService.
 *
 * Each action knows how to parse itself from FCM data and describe
 * itself for logging/notifications.
 */
sealed class PhoneAction {
    abstract val description: String

    /**
     * Make a phone call. Opens dialer with number pre-filled,
     * then accessibility taps the call button.
     */
    data class Call(val number: String) : PhoneAction() {
        override val description = "Call $number"
    }

    /**
     * Send a text message. Opens Messages with recipient + body pre-filled,
     * then accessibility taps the send button.
     */
    data class SendText(val number: String, val body: String) : PhoneAction() {
        override val description = "Text $number"
    }

    /**
     * Read recent messages from a contact's conversation. Opens Messages
     * directly to the thread via smsto: intent, then extracts visible
     * message text via accessibility tree and writes result to a file.
     *
     * @param number Phone number to open thread for (resolved by CLI from contacts.yaml)
     * @param contact Display name for logging/result file (optional, falls back to number)
     */
    data class ReadMessages(val number: String, val contact: String) : PhoneAction() {
        override val description = "Read messages from $contact"
    }

    /**
     * Diagnostic: dump the full accessibility tree of the active window
     * to a JSON file on shared storage. Used to map UI structure before
     * writing navigation code.
     *
     * If app is specified, opens that app first. Otherwise dumps whatever
     * is currently on screen.
     */
    data class DumpTree(val app: String?, val label: String) : PhoneAction() {
        override val description = "Dump tree: $label"
    }

    companion object {
        /**
         * Parse a PhoneAction from FCM data map.
         * Returns null if the data doesn't represent a valid action.
         *
         * Expected FCM fields:
         *   type = "action"
         *   action = "call" | "text" | "read_messages" | "dump_tree"
         *   number = phone number (required for call/text)
         *   body = message text (required for "text", ignored for others)
         *   contact = contact name (required for "read_messages")
         *   app = package name to open (optional for "dump_tree")
         *   label = file label for dump (optional for "dump_tree")
         */
        fun fromFcmData(data: Map<String, String>): PhoneAction? {
            if (data["type"] != "action") return null

            val action = data["action"] ?: return null
            return when (action) {
                "call" -> {
                    val number = data["number"] ?: return null
                    Call(number.trim())
                }
                "text" -> {
                    val number = data["number"] ?: return null
                    val body = data["body"] ?: ""
                    SendText(number.trim(), body)
                }
                "read_messages" -> {
                    val number = data["number"] ?: return null
                    val contact = data["contact"] ?: number
                    ReadMessages(number.trim(), contact.trim())
                }
                "dump_tree" -> {
                    val app = data["app"]
                    val label = data["label"] ?: "screen"
                    DumpTree(app, label)
                }
                else -> null
            }
        }
    }
}
