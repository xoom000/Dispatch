package dev.digitalgnosis.dispatch.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import timber.log.Timber

/**
 * Pure message extraction and parsing helpers for reading SMS conversations.
 * All functions are stateless — no service reference required.
 */
internal object MessageExtractionHelper {

    /**
     * Extract structured messages from the conversation view.
     * Uses contentDescription for sender/timestamp (pattern: "{Sender} said  {msg} {Day} {HH:mm} .")
     * Falls back to raw text for nodes without desc.
     * Filters out date separators, UI chrome, and compose field hints.
     */
    fun extractStructuredMessages(
        node: AccessibilityNodeInfo,
        messages: MutableList<JSONObject>,
        depth: Int,
    ) {
        if (depth > 25) return

        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // Primary: parse contentDescription on message_text nodes
        // Pattern: "{Sender} said  {message} {Day} {HH:mm} ."
        if (rid == "message_text" && desc.contains(" said ")) {
            val msgObj = parseMessageDesc(desc, text)
            if (msgObj != null) {
                messages.add(msgObj)
                return // Don't recurse into children
            }
        }

        // Skip date separators (inside text_separator containers)
        if (rid == "text_separator") return

        // Skip UI chrome and compose field
        if (rid == "compose_message_text") return
        if (rid.startsWith("ComposeRowIcon")) return
        if (rid == "top_app_bar") return

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractStructuredMessages(child, messages, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Legacy flat extraction for fallback. Collects raw text from all nodes.
     */
    fun extractMessageText(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 25) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length > 1) {
            if (!isUiChrome(text)) {
                texts.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractMessageText(child, texts, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Parse a message's contentDescription into structured data.
     * Input: "Blaker Man said  Hey dad Thursday 07:43 ."
     * Output: JSONObject { sender: "Blaker Man", text: "Hey dad", time: "Thursday 07:43" }
     */
    fun parseMessageDesc(desc: String, rawText: String): JSONObject? {
        // Pattern: "{Sender} said  {message} {Day} {HH:mm} ."
        val saidIndex = desc.indexOf(" said ")
        if (saidIndex < 0) return null

        val sender = desc.substring(0, saidIndex).trim()

        // The message content is between "said  " and the trailing timestamp
        val afterSaid = desc.substring(saidIndex + 6).trim()

        // Timestamp is at the end: "Day HH:mm ." or just "HH:mm ."
        val timePattern = Regex("""(.+?)\s+((?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Today|Yesterday)\s+\d{1,2}:\d{2}|\d{1,2}:\d{2})\s*\.\s*$""")
        val match = timePattern.find(afterSaid)

        return if (match != null) {
            JSONObject().apply {
                put("sender", sender)
                put("text", match.groupValues[1].trim())
                put("time", match.groupValues[2].trim())
                put("direction", if (sender == "You") "outgoing" else "incoming")
            }
        } else {
            // Fallback: couldn't parse timestamp, use raw text
            JSONObject().apply {
                put("sender", sender)
                put("text", rawText.ifBlank { afterSaid.removeSuffix(".").trim() })
                put("time", "")
                put("direction", if (sender == "You") "outgoing" else "incoming")
            }
        }
    }

    /**
     * Filter out common UI chrome text that isn't message content.
     */
    fun isUiChrome(text: String): Boolean {
        val lower = text.lowercase().trim()
        val chromeLabels = setOf(
            "messages", "search conversations", "start chat",
            "more options", "back", "video call", "voice call",
            "details", "type message", "type sms message",
            "sms", "rcs", "send sms", "send message",
            "new message", "search", "archived", "unread",
            "rcs message", "text message"
        )
        return lower in chromeLabels
    }
}
