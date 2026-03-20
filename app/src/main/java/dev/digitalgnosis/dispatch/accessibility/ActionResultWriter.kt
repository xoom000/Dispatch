package dev.digitalgnosis.dispatch.accessibility

import android.content.Context
import android.os.Environment
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Writes action result files and tree dumps to shared storage.
 * All files go to /storage/emulated/0/Download/dispatch-logs/.
 */
internal class ActionResultWriter(private val context: Context) {

    private fun logsDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "dispatch-logs"
        )
        dir.mkdirs()
        return dir
    }

    /**
     * Write extracted messages to a JSON file on shared storage.
     * Path: /storage/emulated/0/Download/dispatch-logs/read-result.json
     *
     * Flat format: { contact, messages: ["text1", "text2", ...], ... }
     */
    fun writeResultFile(contact: String, messages: List<String>, error: String?) {
        try {
            val json = JSONObject().apply {
                put("contact", contact)
                put("timestamp", System.currentTimeMillis())
                put("message_count", messages.size)
                put("messages", JSONArray(messages))
                put("format", "flat")
                if (error != null) put("error", error)
            }.toString(2)

            val file = File(logsDir(), "read-result.json")
            file.writeText(json)
            Timber.i("READ: Wrote result to %s (%d bytes, %d messages)",
                file.absolutePath, json.length, messages.size)
        } catch (e: Exception) {
            Timber.e(e, "READ: Failed to write result file")
        }
    }

    /**
     * Write structured messages to result file.
     * Structured format: { contact, messages: [{ sender, text, time, direction }], ... }
     */
    fun writeStructuredResultFile(contact: String, messages: List<JSONObject>, error: String?) {
        try {
            val json = JSONObject().apply {
                put("contact", contact)
                put("timestamp", System.currentTimeMillis())
                put("message_count", messages.size)
                put("messages", JSONArray(messages))
                put("format", "structured")
                if (error != null) put("error", error)
            }.toString(2)

            val file = File(logsDir(), "read-result.json")
            file.writeText(json)
            Timber.i("READ: Wrote structured result to %s (%d bytes, %d messages)",
                file.absolutePath, json.length, messages.size)
        } catch (e: Exception) {
            Timber.e(e, "READ: Failed to write result file")
        }
    }

    /**
     * Write a single-tree dump file.
     * Returns true on success so the caller can invoke actionSuccess/actionFailed.
     */
    fun writeDumpFile(label: String, pkg: String, treeJson: JSONObject): Boolean {
        val wrapper = JSONObject().apply {
            put("label", label)
            put("package", pkg)
            put("timestamp", System.currentTimeMillis())
            put("tree", treeJson)
        }

        return try {
            val file = File(logsDir(), "tree-${label}.json")
            file.writeText(wrapper.toString(2))
            Timber.i("DUMP: Wrote tree to %s (%d bytes)", file.absolutePath, file.length())
            true
        } catch (e: Exception) {
            Timber.e(e, "DUMP: Failed to write tree file")
            false
        }
    }

    /**
     * Dump ALL visible windows to a single JSON file for diagnostic purposes.
     * Used when the target package isn't found — shows exactly what IS available.
     * Returns a summary string describing what was written.
     */
    fun dumpAllWindows(
        label: String,
        targetPkg: String,
        windows: List<AccessibilityWindowInfo>,
    ): String {
        val windowsArray = JSONArray()
        try {
            for (window in windows) {
                val windowRoot = window.root ?: continue
                val pkg = windowRoot.packageName?.toString() ?: "unknown"
                val windowObj = JSONObject().apply {
                    put("package", pkg)
                    put("type", window.type)
                    put("title", window.title?.toString())
                    put("layer", window.layer)
                    put("tree", AccessibilityTreeSerializer.dumpTreeToJson(windowRoot, 0))
                }
                windowsArray.put(windowObj)
                try { windowRoot.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Timber.w("DUMP: Error enumerating windows: %s", e.message)
        }

        val wrapper = JSONObject().apply {
            put("label", label)
            put("target_package", targetPkg)
            put("target_found", false)
            put("timestamp", System.currentTimeMillis())
            put("window_count", windowsArray.length())
            put("windows", windowsArray)
        }

        return try {
            val file = File(logsDir(), "tree-${label}.json")
            file.writeText(wrapper.toString(2))
            Timber.i("DUMP: Wrote %d windows to %s (%d bytes)", windowsArray.length(), file.absolutePath, file.length())
            "Tree dumped: $label (${windowsArray.length()} windows, target $targetPkg not found)"
        } catch (e: Exception) {
            Timber.e(e, "DUMP: Failed to write tree file")
            ""
        }
    }
}
