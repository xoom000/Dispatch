package dev.digitalgnosis.dispatch.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Pure tree-search helpers for accessibility node traversal.
 * All functions are stateless — no service reference required.
 */
internal object AccessibilityNodeFinder {

    /**
     * Check if a root node belongs to the correct app for this action.
     * Prevents wasting retries on the wrong app's tree (e.g., Termux).
     */
    fun isCorrectAppRoot(root: AccessibilityNodeInfo, action: PhoneAction): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        return when (action) {
            is PhoneAction.Call -> pkg.contains("dialer", ignoreCase = true)
            is PhoneAction.SendText -> pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true)
            is PhoneAction.ReadMessages -> pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true)
            is PhoneAction.DumpTree -> action.app == null || pkg == action.app
        }
    }

    /**
     * Search all visible windows for the target app's root node.
     * More reliable than rootInActiveWindow because:
     * - rootInActiveWindow only returns the window with input focus
     * - During app transitions, no window may have focus
     * - The target app may be visible but not yet focused
     *
     * @param windows the list of windows from AccessibilityService.getWindows()
     */
    fun findTargetAppRoot(
        action: PhoneAction,
        windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    ): AccessibilityNodeInfo? {
        val targetPackage = when (action) {
            is PhoneAction.Call -> "com.google.android.dialer"
            is PhoneAction.SendText -> "com.google.android.apps.messaging"
            is PhoneAction.ReadMessages -> "com.google.android.apps.messaging"
            is PhoneAction.DumpTree -> action.app ?: return null
        }

        try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == targetPackage) {
                    Timber.i("ACTION: Found target app root via windows API: pkg=%s", targetPackage)
                    return root
                }
                val pkg = root.packageName?.toString() ?: ""
                if (action is PhoneAction.Call && pkg.contains("dialer", ignoreCase = true)) {
                    Timber.i("ACTION: Found dialer root via windows API: pkg=%s", pkg)
                    return root
                }
                if ((action is PhoneAction.SendText || action is PhoneAction.ReadMessages) &&
                    (pkg.contains("messaging", ignoreCase = true) ||
                            pkg.contains("mms", ignoreCase = true))) {
                    Timber.i("ACTION: Found messaging root via windows API: pkg=%s", pkg)
                    return root
                }
                try { root.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Timber.w("ACTION: Failed to enumerate windows: %s", e.message)
        }
        return null
    }

    /**
     * Search all visible windows for a specific package name.
     *
     * @param windows the list of windows from AccessibilityService.getWindows()
     */
    fun findWindowByPackage(
        targetPkg: String,
        windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    ): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val windowRoot = window.root ?: continue
                val pkg = windowRoot.packageName?.toString() ?: ""
                if (pkg == targetPkg) {
                    return windowRoot
                }
                try { windowRoot.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Timber.w("DUMP: Failed to enumerate windows: %s", e.message)
        }
        return null
    }

    /**
     * Use the system-level findAccessibilityNodeInfosByText() API.
     * Searches ALL semantics including Jetpack Compose semantics labels
     * that manual tree traversal misses. Returns the first actionable match.
     */
    fun findNodeBySystemSearch(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        if (matches.isNullOrEmpty()) return null

        Timber.d("System search for '%s' found %d matches", text, matches.size)

        for (node in matches) {
            val actions = node.actionList.map { it.id }
            Timber.d("  Match: desc='%s' text='%s' id=%s clickable=%s actions=%s",
                node.contentDescription, node.text,
                node.viewIdResourceName, node.isClickable, actions)

            if (node.isClickable || actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)) {
                matches.filter { it != node }.forEach { try { it.recycle() } catch (_: Exception) {} }
                return node
            }
        }

        Timber.d("No clickable match, trying first result anyway")
        val first = matches.first()
        matches.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
        return first
    }

    /**
     * Deep recursive search that does NOT require isClickable.
     * For Compose UIs where clickable state may not be exposed.
     */
    fun findNodeByDescriptionDeep(
        root: AccessibilityNodeInfo,
        text: String,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > 15) return null
        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByDescriptionDeep(child, text, depth + 1)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Recursively search for a clickable node whose content description
     * contains the given text (case-insensitive).
     */
    fun findNodeByContentDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true) && root.isClickable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, text)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Recursively search for a clickable node whose resource ID
     * contains the given substring.
     */
    fun findNodeByResourceId(root: AccessibilityNodeInfo, idSubstring: String): AccessibilityNodeInfo? {
        val resId = root.viewIdResourceName
        if (resId != null && resId.contains(idSubstring, ignoreCase = true) && root.isClickable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByResourceId(child, idSubstring)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Recursively search for a clickable node whose visible text
     * contains the given string (case-insensitive).
     */
    fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = root.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true) && root.isClickable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findClickableByText(child, text)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Recursively search for any node whose text or content description
     * contains the given string. Does NOT require isClickable.
     */
    fun findNodeByTextContaining(
        root: AccessibilityNodeInfo,
        text: String,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val nodeText = root.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true)) return root
        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByTextContaining(child, text, depth + 1)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Walk up the tree to find the nearest clickable ancestor.
     * Useful when a text node inside a list item is found — the list item
     * is what's clickable, not the text itself.
     */
    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent ?: return null
        var depth = 0
        while (depth < 10) {
            if (current.isClickable) return current
            val next = current.parent
            if (next == null) {
                try { current.recycle() } catch (_: Exception) {}
                return null
            }
            try { current.recycle() } catch (_: Exception) {}
            current = next
            depth++
        }
        try { current.recycle() } catch (_: Exception) {}
        return null
    }

    /**
     * Find the main scrollable container (message list) in the conversation view.
     * Skips the compose field's scrollable by preferring larger scroll areas.
     */
    fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable && root.childCount > 3) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findScrollableContainer(child)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }
}
