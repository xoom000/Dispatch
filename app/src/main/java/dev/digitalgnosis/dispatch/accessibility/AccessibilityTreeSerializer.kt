package dev.digitalgnosis.dispatch.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Pure serialization helpers for accessibility tree dumps.
 * All functions are stateless — no service reference required.
 */
internal object AccessibilityTreeSerializer {

    /**
     * Recursively serialize the accessibility tree to JSON.
     * Captures ALL properties of each node for offline analysis.
     */
    fun dumpTreeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val obj = JSONObject()
        obj.put("class", node.className?.toString()?.substringAfterLast('.') ?: "?")
        obj.put("text", node.text?.toString())
        obj.put("desc", node.contentDescription?.toString())
        obj.put("id", node.viewIdResourceName?.substringAfterLast('/'))
        obj.put("fullId", node.viewIdResourceName)
        obj.put("clickable", node.isClickable)
        obj.put("editable", node.isEditable)
        obj.put("focusable", node.isFocusable)
        obj.put("scrollable", node.isScrollable)
        obj.put("enabled", node.isEnabled)
        obj.put("selected", node.isSelected)
        obj.put("checked", node.isChecked)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        obj.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")

        val actions = node.actionList.map { it.id }
        obj.put("actions", JSONArray(actions))

        if (depth < 20 && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(dumpTreeToJson(child, depth + 1))
                try { child.recycle() } catch (_: Exception) {}
            }
            obj.put("children", children)
        }

        return obj
    }

    /**
     * Dump the accessibility tree for debugging via Timber.
     * Called when button search is about to fail, so we can see
     * what nodes actually exist.
     */
    fun dumpTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return  // Compose UIs can be deep

        val indent = "  ".repeat(depth)
        val actions = node.actionList.map { it.id }
        Timber.d("TREE %s[%s] text='%s' desc='%s' id=%s click=%s actions=%s",
            indent,
            node.className?.toString()?.substringAfterLast('.') ?: "?",
            node.text?.toString()?.take(30),
            node.contentDescription?.toString()?.take(30),
            node.viewIdResourceName?.substringAfterLast('/'),
            node.isClickable,
            actions)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpTree(child, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }
}
