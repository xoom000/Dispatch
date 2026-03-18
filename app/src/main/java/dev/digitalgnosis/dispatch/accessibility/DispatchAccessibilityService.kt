package dev.digitalgnosis.dispatch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Accessibility service that executes phone actions (calls, texts) on behalf
 * of DG agents. Actions arrive via FCM, get routed here, and are executed
 * through a combination of intents (to open the right app) and accessibility
 * tree automation (to tap the right button).
 *
 * Architecture:
 *   1. FCM data message arrives with type="action"
 *   2. DispatchFcmService parses PhoneAction and calls executeAction()
 *   3. This service launches the appropriate intent (ACTION_DIAL / ACTION_SENDTO)
 *   4. When the target app window appears, we search the accessibility tree
 *      for the call/send button and tap it
 *   5. Notification posted with result
 *
 * Setup: User must manually enable this service in Settings > Accessibility.
 * The service runs as a system-privileged foreground service and can start
 * activities and interact with any app's UI tree.
 */
class DispatchAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** The action we're currently trying to execute. */
    @Volatile
    private var pendingAction: PhoneAction? = null

    /** How many times we've retried finding the target button. */
    private var retryCount = 0

    /** Whether we've already launched the intent for the pending action. */
    private var intentLaunched = false

    /** Whether we've successfully populated the compose field (text actions only). */
    private var textPopulated = false

    /** State machine for ReadMessages — simplified: open thread directly, then read. */
    private enum class ReadState {
        WAITING_FOR_THREAD,  // Waiting for conversation thread to load
        READING              // Extract messages from conversation view
    }
    private var readState = ReadState.WAITING_FOR_THREAD

    /** Guard against concurrent tryExecutePendingAction calls. */
    private var tryInProgress = false

    /** Timestamp of last retry attempt — prevents event-driven retry burn. */
    private var lastRetryTimeMs = 0L

    companion object {
        /** Static reference to the running service instance. */
        @Volatile
        private var instance: DispatchAccessibilityService? = null

        private const val MAX_RETRIES = 12
        private const val RETRY_DELAY_MS = 500L
        private const val MIN_RETRY_INTERVAL_MS = 400L
        private const val CHANNEL_ID = "dispatch_actions"

        /**
         * Check if the accessibility service is currently enabled and connected.
         */
        fun isEnabled(): Boolean = instance != null

        /**
         * Execute a phone action. Called from DispatchFcmService.
         *
         * @return true if the service is enabled and action was accepted,
         *         false if the service isn't enabled.
         */
        fun executeAction(action: PhoneAction): Boolean {
            val service = instance
            if (service == null) {
                Timber.w("ACTION REJECTED: AccessibilityService not enabled — %s", action.description)
                return false
            }
            service.handleAction(action)
            return true
        }
    }

    // ---- Lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Log what XML config provided (capabilities come from XML only)
        val info = serviceInfo
        Timber.i("A11Y pre-config: flags=0x%x capabilities=0x%x", info.flags, info.capabilities)

        // Set flags programmatically (these are allowed at runtime)
        // Capabilities (canRetrieveWindowContent, canPerformGestures) come from XML only
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info

        createNotificationChannel()

        // Diagnostic: verify final state
        try {
            val postInfo = serviceInfo
            val windowCount = windows.size
            val rootAvail = rootInActiveWindow != null
            Timber.i("=== DispatchAccessibilityService CONNECTED === windows=%d rootAvail=%s flags=0x%x capabilities=0x%x",
                windowCount, rootAvail, postInfo.flags, postInfo.capabilities)
        } catch (e: Exception) {
            Timber.i("=== DispatchAccessibilityService CONNECTED === (diagnostics failed: %s)", e.message)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || pendingAction == null) return

        // Only log window state changes (content changes are too noisy)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Timber.d("A11Y window changed: pkg=%s class=%s",
                event.packageName, event.className)
        }

        // DO NOT trigger retries from events — timer-based retries only.
        // Event-driven retries caused retry burn (all 12 in <500ms).
        // The timer in retryOrGiveUp handles all retries at proper intervals.
    }

    override fun onInterrupt() {
        Timber.w("DispatchAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        Timber.i("=== DispatchAccessibilityService DESTROYED ===")
    }

    // ---- Action handling ----

    private fun handleAction(action: PhoneAction) {
        // Ensure we run on the main thread — FCM calls from background thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { handleAction(action) }
            return
        }

        // Cancel any in-flight action
        if (pendingAction != null) {
            Timber.w("Cancelling previous action: %s", pendingAction?.description)
            handler.removeCallbacksAndMessages(null)
        }

        Timber.i("ACTION START: %s", action.description)
        pendingAction = action
        retryCount = 0
        intentLaunched = false
        textPopulated = false
        tryInProgress = false
        lastRetryTimeMs = 0L
        readState = ReadState.WAITING_FOR_THREAD

        launchIntent(action)
    }

    private fun launchIntent(action: PhoneAction) {
        val intent = when (action) {
            is PhoneAction.Call -> {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.number}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            is PhoneAction.SendText -> {
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.number}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("sms_body", action.body)
                    putExtra(Intent.EXTRA_TEXT, action.body)
                }
            }
            is PhoneAction.ReadMessages -> {
                // Open Google Messages directly to the contact's thread via smsto: intent.
                // Number is resolved by CLI from contacts.yaml — no UI search needed.
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.number}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            is PhoneAction.DumpTree -> {
                if (action.app != null) {
                    // Open specified app (with fallback to known component intents)
                    getLaunchIntent(
                        action.app,
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ) ?: run {
                        // No launch intent, just dump current screen
                        Timber.i("DUMP: No launch intent for %s, dumping current screen", action.app)
                        handler.postDelayed({ tryExecutePendingAction() }, 500)
                        return
                    }
                } else {
                    // No app specified — dump whatever is on screen now
                    Timber.i("DUMP: No app specified, dumping current screen immediately")
                    handler.postDelayed({ tryExecutePendingAction() }, 500)
                    return
                }
            }
        }

        try {
            startActivity(intent)
            intentLaunched = true
            Timber.i("ACTION intent launched: %s", action.description)

            // Give the app time to render, then start trying
            val delay = when (action) {
                is PhoneAction.Call -> 2000L
                is PhoneAction.SendText -> 3000L  // Messages app loads slower with Compose
                is PhoneAction.ReadMessages -> 3000L  // Needs time to fully render conversation list
                is PhoneAction.DumpTree -> 2000L
            }
            handler.postDelayed({ tryExecutePendingAction() }, delay)
        } catch (e: Exception) {
            Timber.e(e, "ACTION FAILED: couldn't launch intent for %s", action.description)
            actionFailed("Couldn't open app: ${e.message}")
        }
    }

    private fun tryExecutePendingAction() {
        val action = pendingAction ?: return

        // Debounce: prevent re-entrant calls from burning through retries
        if (tryInProgress) return
        val now = System.currentTimeMillis()
        if (lastRetryTimeMs > 0 && now - lastRetryTimeMs < MIN_RETRY_INTERVAL_MS) return
        tryInProgress = true
        lastRetryTimeMs = now

        try {
            // Try rootInActiveWindow first, but verify it's the right app.
            // If the user has another app focused (e.g. Termux), rootInActiveWindow
            // returns that app's tree instead of our target. Fall back to windows API.
            val activeRoot = rootInActiveWindow
            val rootNode = if (activeRoot != null && isCorrectAppRoot(activeRoot, action)) {
                activeRoot
            } else {
                activeRoot?.let { try { it.recycle() } catch (_: Exception) {} }
                findTargetAppRoot(action)
            }

            if (rootNode == null) {
                // Log what windows we can see for debugging
                if (retryCount == 0 || retryCount % 5 == 0) {
                    try {
                        val windowList = windows
                        Timber.w("ACTION retry %d: rootInActiveWindow=null, visible windows (%d):",
                            retryCount, windowList.size)
                        windowList.forEach { w ->
                            val pkg = try { w.root?.packageName } catch (_: Exception) { null }
                            Timber.w("  window: type=%d title='%s' pkg=%s layer=%d",
                                w.type, w.title, pkg, w.layer)
                        }
                    } catch (e: Exception) {
                        Timber.w("ACTION retry %d: rootInActiveWindow=null, can't enumerate windows: %s",
                            retryCount, e.message)
                    }
                }
                retryOrGiveUp("No root node available")
                return
            }

            Timber.i("ACTION: got root node on retry %d (pkg=%s)", retryCount, rootNode.packageName)

            try {
                when (action) {
                    is PhoneAction.Call -> tryTapCallButton(rootNode, action)
                    is PhoneAction.SendText -> tryTapSendButton(rootNode, action)
                    is PhoneAction.ReadMessages -> handleReadMessages(rootNode, action)
                    is PhoneAction.DumpTree -> handleDumpTree(rootNode, action)
                }
            } finally {
                try { rootNode.recycle() } catch (_: Exception) {}
            }
        } finally {
            tryInProgress = false
        }
    }

    /**
     * Check if a root node belongs to the correct app for this action.
     * Prevents wasting retries on the wrong app's tree (e.g., Termux).
     */
    private fun isCorrectAppRoot(root: AccessibilityNodeInfo, action: PhoneAction): Boolean {
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
     * This is more reliable than rootInActiveWindow because:
     * - rootInActiveWindow only returns the window with input focus
     * - During app transitions, no window may have focus
     * - The target app may be visible but not yet focused
     */
    private fun findTargetAppRoot(action: PhoneAction): AccessibilityNodeInfo? {
        val targetPackage = when (action) {
            is PhoneAction.Call -> "com.google.android.dialer"
            is PhoneAction.SendText -> "com.google.android.apps.messaging"
            is PhoneAction.ReadMessages -> "com.google.android.apps.messaging"
            is PhoneAction.DumpTree -> action.app ?: return null // No specific app to find
        }

        try {
            val windowList = windows
            for (window in windowList) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == targetPackage) {
                    Timber.i("ACTION: Found target app root via windows API: pkg=%s", targetPackage)
                    return root
                }
                // Also check for alternate package names
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

    // ---- Call action ----

    private fun tryTapCallButton(root: AccessibilityNodeInfo, action: PhoneAction.Call) {
        // Strategy 1: Content description containing "call"
        val callButton = findNodeByContentDescription(root, "call")
            // Strategy 2: Content description containing "dial"
            ?: findNodeByContentDescription(root, "dial")
            // Strategy 3: Known Pixel dialer resource IDs
            ?: findNodeByResourceId(root, "dialpad_floating_action_button")
            ?: findNodeByResourceId(root, "call_button")
            ?: findNodeByResourceId(root, "floating_action_button")
            // Strategy 4: Clickable with text "Call"
            ?: findClickableByText(root, "call")

        if (callButton != null) {
            tapNode(callButton, action.description)
        } else {
            // Dump tree on later retries for debugging
            if (retryCount == MAX_RETRIES - 2) {
                Timber.w("ACTION DEBUG: Dumping accessibility tree for call button search:")
                dumpTree(root, 0)
            }
            retryOrGiveUp("Call button not found in accessibility tree")
        }
    }

    // ---- Text/SMS action ----

    private fun tryTapSendButton(root: AccessibilityNodeInfo, action: PhoneAction.SendText) {
        // Two-phase approach for Google Messages (Jetpack Compose UI):
        //   Phase 1: Populate the compose field via ACTION_SET_TEXT
        //            (sms_body intent extra is unreliable with Compose)
        //   Phase 2: Find and tap the actual send button

        // Phase 1: Populate compose field if we haven't yet
        if (!textPopulated) {
            val composeField = findComposeField(root)
            if (composeField != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.body
                    )
                }
                val success = composeField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Timber.i("ACTION: set compose text: success=%s, body='%s...'",
                    success, action.body.take(40))
                try { composeField.recycle() } catch (_: Exception) {}

                if (success) {
                    textPopulated = true
                    // Give UI time to react — send button appears/enables after text is set
                    handler.postDelayed({ tryExecutePendingAction() }, 800)
                    return
                } else {
                    Timber.w("ACTION: ACTION_SET_TEXT failed, will retry")
                    retryOrGiveUp("Compose field found but SET_TEXT failed")
                    return
                }
            } else {
                Timber.d("ACTION: compose field not found on retry %d", retryCount)
                // Dump tree early if we can't find compose field after several tries
                if (retryCount == 5) {
                    Timber.w("ACTION DEBUG: Dumping tree — can't find compose field:")
                    dumpTree(root, 0)
                }
                retryOrGiveUp("Compose field not found")
                return
            }
        }

        // Phase 2: Find and tap the send button (text is already populated)
        // Google Messages uses Jetpack Compose — standard View properties (text,
        // contentDescription, resourceId) are often null. But Compose exposes
        // semantics through the system accessibility API findAccessibilityNodeInfosByText(),
        // which searches Compose semantics labels that our manual recursion misses.

        val sendButton =
            // Strategy 1: System-level text search — "Send SMS" or "Send message"
            findNodeBySystemSearch(root, "Send")
            // Strategy 2: Content description containing "send"
            ?: findNodeByContentDescription(root, "send")
            // Strategy 3: Known Google Messages resource IDs
            ?: findNodeByResourceId(root, "send_message_button")
            ?: findNodeByResourceId(root, "send_button_container")
            ?: findNodeByResourceId(root, "compose_message_send")
            // Strategy 4: Content description with "SMS"
            ?: findNodeByContentDescription(root, "sms")
            // Strategy 5: Clickable with text "Send" (relaxed)
            ?: findClickableByText(root, "send")
            // Strategy 6: Deep recursive without clickable check
            ?: findNodeByDescriptionDeep(root, "send", 0)

        if (sendButton != null) {
            tapNode(sendButton, action.description)
        } else {
            if (retryCount == MAX_RETRIES - 2) {
                Timber.w("ACTION DEBUG: Dumping accessibility tree for send button search:")
                dumpTree(root, 0)
            }
            retryOrGiveUp("Send button not found (text IS populated)")
        }
    }

    /**
     * Find the message compose/input field in the accessibility tree.
     * Looks for editable text fields — in a messaging app, this is the
     * message input where the user types their text.
     */
    private fun findComposeField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Direct check: editable field (works for both platform EditText and Compose TextField)
        if (root.isEditable) {
            Timber.d("Found compose field (isEditable): class=%s desc='%s' id=%s",
                root.className, root.contentDescription, root.viewIdResourceName)
            return root
        }

        // Check for EditText class name
        val className = root.className?.toString() ?: ""
        if (className == "android.widget.EditText") {
            Timber.d("Found compose field (EditText): desc='%s' id=%s",
                root.contentDescription, root.viewIdResourceName)
            return root
        }

        // Recurse into children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findComposeField(child)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    // ---- Read Messages action ----

    /**
     * Simplified handler for reading messages from a contact's conversation.
     *
     * The CLI resolves contact name → phone number via contacts.yaml.
     * The smsto: intent opens the thread directly — no UI search needed.
     * State machine: WAITING_FOR_THREAD -> READING
     */
    private fun handleReadMessages(root: AccessibilityNodeInfo, action: PhoneAction.ReadMessages) {
        val rootPkg = root.packageName?.toString() ?: ""
        if (!rootPkg.contains("messaging", ignoreCase = true) && !rootPkg.contains("mms", ignoreCase = true)) {
            Timber.w("READ: Wrong app in foreground: %s (need Google Messages)", rootPkg)
            retryOrGiveUp("Wrong app: $rootPkg (waiting for Google Messages)")
            return
        }

        when (readState) {
            ReadState.WAITING_FOR_THREAD -> {
                // Check if we're in a conversation thread (has message_list or compose field)
                val messageList = findNodeByResourceId(root, "message_list")
                val composeField = findNodeByResourceId(root, "compose_message_text")

                if (messageList != null || composeField != null) {
                    Timber.i("READ: Thread loaded for '%s', extracting messages", action.contact)
                    try { messageList?.recycle() } catch (_: Exception) {}
                    try { composeField?.recycle() } catch (_: Exception) {}
                    readState = ReadState.READING
                    retryCount = 0
                    // Small delay for messages to finish rendering
                    handler.postDelayed({ tryExecutePendingAction() }, 500)
                } else {
                    // Still on conversation list or loading — the smsto: intent may need
                    // a moment to open the thread, or the number may not match a thread
                    if (retryCount >= 10) {
                        Timber.w("READ: Thread never loaded for number '%s' after %d retries", action.number, retryCount)
                        writeResultFile(action.contact, emptyList(), "Could not open thread for ${action.contact} (${action.number})")
                        actionFailed("Thread didn't open for ${action.contact}")
                        return
                    }
                    retryOrGiveUp("Waiting for thread to load")
                }
            }

            ReadState.READING -> {
                val scrollable = findScrollableContainer(root)
                val searchRoot = scrollable ?: root

                // Try structured extraction first (uses contentDescription for sender/time)
                val structured = mutableListOf<JSONObject>()
                extractStructuredMessages(searchRoot, structured, 0)

                if (structured.isNotEmpty()) {
                    Timber.i("READ: Extracted %d structured messages from '%s'", structured.size, action.contact)
                    writeStructuredResultFile(action.contact, structured, null)
                    actionSuccess("Read ${structured.size} messages from ${action.contact}")
                    return
                }

                // Fallback to flat text extraction
                val messages = mutableListOf<String>()
                extractMessageText(searchRoot, messages, 0)

                if (messages.isEmpty()) {
                    if (retryCount < 5) {
                        retryOrGiveUp("No messages found yet (conversation may still be loading)")
                        return
                    }
                    writeResultFile(action.contact, emptyList(), "No messages found in conversation view")
                    actionFailed("No messages found in ${action.contact}'s conversation")
                    return
                }

                Timber.i("READ: Extracted %d text items from '%s' (flat fallback)", messages.size, action.contact)
                writeResultFile(action.contact, messages, null)
                actionSuccess("Read ${messages.size} messages from ${action.contact}")
            }
        }
    }

    /**
     * Tap a conversation node. Tries ACTION_CLICK, clickable ancestor, then gesture.
     */
    private fun tapConversationNode(node: AccessibilityNodeInfo) {
        var tapped = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!tapped) {
            val clickableAncestor = findClickableAncestor(node)
            if (clickableAncestor != null) {
                tapped = clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Timber.d("READ: tapped clickable ancestor: %s", tapped)
                try { clickableAncestor.recycle() } catch (_: Exception) {}
            }
        }
        if (!tapped) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                Timber.d("READ: gesture tap at (%d, %d)", bounds.centerX(), bounds.centerY())
                gestureClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }
        try { node.recycle() } catch (_: Exception) {}
    }

    /**
     * Find a conversation in the list by contact name.
     * Uses system-level search (catches Compose semantics) then manual traversal.
     */
    private fun findConversationByContact(
        root: AccessibilityNodeInfo,
        contact: String,
    ): AccessibilityNodeInfo? {
        // Strategy 1: System search — finds Compose Text nodes with matching content
        val systemMatch = root.findAccessibilityNodeInfosByText(contact)
        if (!systemMatch.isNullOrEmpty()) {
            Timber.d("READ: System search for '%s' found %d matches", contact, systemMatch.size)
            // Return the first match (should be the conversation title)
            val first = systemMatch.first()
            systemMatch.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
            return first
        }

        // Strategy 2: Manual tree traversal — look for text containing contact name
        return findNodeByTextContaining(root, contact, 0)
    }

    /**
     * Recursively search for any node whose text contains the given string.
     * Does NOT require isClickable (we'll find the clickable ancestor separately).
     */
    private fun findNodeByTextContaining(
        root: AccessibilityNodeInfo,
        text: String,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null

        val nodeText = root.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true)) {
            return root
        }

        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true)) {
            return root
        }

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
     * Useful when we found a text node inside a list item — the list item
     * is what's clickable, not the text itself.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
    private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable && root.childCount > 3) {
            // Likely the message list (has many children), not the compose field
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findScrollableContainer(child)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Extract message text from the conversation view's accessibility tree.
     * Collects visible text, filtering out common UI labels like toolbar
     * titles, button labels, and compose field hints.
     */
    /**
     * Extract structured messages from the conversation view.
     * Uses contentDescription for sender/timestamp (pattern: "{Sender} said  {msg} {Day} {HH:mm} .")
     * Falls back to raw text for nodes without desc.
     * Filters out date separators, UI chrome, and compose field hints.
     */
    private fun extractStructuredMessages(
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
     * Parse a message's contentDescription into structured data.
     * Input: "Blaker Man said  Hey dad Thursday 07:43 ."
     * Output: JSONObject { sender: "Blaker Man", text: "Hey dad", time: "Thursday 07:43" }
     */
    private fun parseMessageDesc(desc: String, rawText: String): JSONObject? {
        // Pattern: "{Sender} said  {message} {Day} {HH:mm} ."
        val saidIndex = desc.indexOf(" said ")
        if (saidIndex < 0) return null

        val sender = desc.substring(0, saidIndex).trim()

        // The message content is between "said  " and the trailing timestamp
        val afterSaid = desc.substring(saidIndex + 6).trim()

        // Timestamp is at the end: "Day HH:mm ." or just "HH:mm ."
        // Try to extract it with regex
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
     * Legacy flat extraction for fallback. Collects raw text from all nodes.
     */
    private fun extractMessageText(
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
     * Filter out common UI chrome text that isn't message content.
     */
    private fun isUiChrome(text: String): Boolean {
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

    /**
     * Write extracted messages to a JSON file on shared storage.
     * Path: /storage/emulated/0/Download/dispatch-logs/read-result.json
     *
     * Structured format (when contentDescription parsing works):
     * { contact, messages: [{ sender, text, time, direction }], ... }
     *
     * Flat format (fallback):
     * { contact, messages: ["text1", "text2", ...], ... }
     */
    private fun writeResultFile(
        contact: String,
        messages: List<String>,
        error: String?,
    ) {
        try {
            val json = JSONObject().apply {
                put("contact", contact)
                put("timestamp", System.currentTimeMillis())
                put("message_count", messages.size)
                put("messages", JSONArray(messages))
                put("format", "flat")
                if (error != null) put("error", error)
            }.toString(2)

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "dispatch-logs"
            )
            dir.mkdirs()
            val file = File(dir, "read-result.json")
            file.writeText(json)
            Timber.i("READ: Wrote result to %s (%d bytes, %d messages)",
                file.absolutePath, json.length, messages.size)
        } catch (e: Exception) {
            Timber.e(e, "READ: Failed to write result file")
        }
    }

    /**
     * Write structured messages to result file.
     */
    private fun writeStructuredResultFile(
        contact: String,
        messages: List<JSONObject>,
        error: String?,
    ) {
        try {
            val json = JSONObject().apply {
                put("contact", contact)
                put("timestamp", System.currentTimeMillis())
                put("message_count", messages.size)
                put("messages", JSONArray(messages))
                put("format", "structured")
                if (error != null) put("error", error)
            }.toString(2)

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "dispatch-logs"
            )
            dir.mkdirs()
            val file = File(dir, "read-result.json")
            file.writeText(json)
            Timber.i("READ: Wrote structured result to %s (%d bytes, %d messages)",
                file.absolutePath, json.length, messages.size)
        } catch (e: Exception) {
            Timber.e(e, "READ: Failed to write result file")
        }
    }

    // ---- Tree Dump (diagnostic) ----

    /**
     * Dump the accessibility tree to a JSON file. When --app is specified,
     * searches ALL windows for that package (doesn't rely on rootInActiveWindow).
     * If the target package isn't found, dumps ALL available windows for diagnosis.
     *
     * Output: /storage/emulated/0/Download/dispatch-logs/tree-{label}.json
     */
    private fun handleDumpTree(root: AccessibilityNodeInfo, action: PhoneAction.DumpTree) {
        val targetPkg = action.app

        if (targetPkg != null) {
            // Search all windows for the target package
            val targetRoot = findWindowByPackage(targetPkg)
            if (targetRoot != null) {
                val pkg = targetRoot.packageName?.toString() ?: targetPkg
                Timber.i("DUMP: Found target window for pkg=%s label=%s", pkg, action.label)
                writeDumpFile(action.label, pkg, dumpTreeToJson(targetRoot, 0))
                try { targetRoot.recycle() } catch (_: Exception) {}
                return
            }

            // Target not found — dump ALL windows so we can see what's available
            Timber.w("DUMP: Target pkg=%s not in any window, dumping all windows", targetPkg)
            dumpAllWindows(action.label, targetPkg)
            return
        }

        // No app specified — dump whatever rootInActiveWindow gave us
        val pkg = root.packageName?.toString() ?: "unknown"
        Timber.i("DUMP: Capturing tree for pkg=%s label=%s", pkg, action.label)
        writeDumpFile(action.label, pkg, dumpTreeToJson(root, 0))
    }

    /**
     * Search all visible windows for a specific package name.
     * Returns the root AccessibilityNodeInfo if found, null otherwise.
     */
    private fun findWindowByPackage(targetPkg: String): AccessibilityNodeInfo? {
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
     * Dump ALL visible windows to a single JSON file for diagnostic purposes.
     * Used when the target package isn't found — shows exactly what IS available.
     */
    private fun dumpAllWindows(label: String, targetPkg: String) {
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
                    put("tree", dumpTreeToJson(windowRoot, 0))
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

        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "dispatch-logs"
            )
            dir.mkdirs()
            val file = File(dir, "tree-${label}.json")
            file.writeText(wrapper.toString(2))
            Timber.i("DUMP: Wrote %d windows to %s (%d bytes)", windowsArray.length(), file.absolutePath, file.length())
            actionSuccess("Tree dumped: $label (${windowsArray.length()} windows, target $targetPkg not found)")
        } catch (e: Exception) {
            Timber.e(e, "DUMP: Failed to write tree file")
            actionFailed("Tree dump write failed: ${e.message}")
        }
    }

    /**
     * Write a single-tree dump file.
     */
    private fun writeDumpFile(label: String, pkg: String, treeJson: JSONObject) {
        val wrapper = JSONObject().apply {
            put("label", label)
            put("package", pkg)
            put("timestamp", System.currentTimeMillis())
            put("tree", treeJson)
        }

        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "dispatch-logs"
            )
            dir.mkdirs()
            val file = File(dir, "tree-${label}.json")
            file.writeText(wrapper.toString(2))
            Timber.i("DUMP: Wrote tree to %s (%d bytes)", file.absolutePath, file.length())
            actionSuccess("Tree dumped: $label (${file.length()} bytes)")
        } catch (e: Exception) {
            Timber.e(e, "DUMP: Failed to write tree file")
            actionFailed("Tree dump write failed: ${e.message}")
        }
    }

    /**
     * Recursively serialize the accessibility tree to JSON.
     * Captures ALL properties of each node for offline analysis.
     */
    private fun dumpTreeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
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
     * Fire a gesture click at screen coordinates. Used as last-resort tap.
     */
    private fun gestureClick(x: Float, y: Float) {
        val clickPath = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---- Tap execution ----

    /**
     * Attempt to tap a node. Tries ACTION_CLICK first (works for traditional Views).
     * If that fails (common with Jetpack Compose), falls back to gesture injection
     * which simulates an actual finger tap at the node's screen coordinates.
     */
    private fun tapNode(node: AccessibilityNodeInfo, actionDescription: String) {
        Timber.i("Attempting tap on node: desc='%s', id=%s, class=%s, clickable=%s",
            node.contentDescription, node.viewIdResourceName,
            node.className, node.isClickable)

        // Try 1: Standard ACTION_CLICK
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Timber.i("ACTION RESULT: %s — ACTION_CLICK succeeded", actionDescription)
            actionSuccess(actionDescription)
            try { node.recycle() } catch (_: Exception) {}
            return
        }

        Timber.w("ACTION_CLICK failed for %s, falling back to gesture tap", actionDescription)

        // Try 2: Gesture injection — simulate real touch at node's screen coordinates
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        try { node.recycle() } catch (_: Exception) {}

        if (bounds.isEmpty) {
            Timber.e("Node has empty bounds — can't gesture tap")
            actionFailed("Found button but has no screen bounds")
            return
        }

        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        Timber.i("Gesture tap at (%f, %f) for %s", x, y, actionDescription)

        val clickPath = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()

        val desc = actionDescription  // capture for callback
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Timber.i("ACTION RESULT: %s — gesture tap COMPLETED at (%f, %f)", desc, x, y)
                actionSuccess(desc)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Timber.e("ACTION RESULT: %s — gesture tap CANCELLED", desc)
                actionFailed("Gesture tap cancelled by system")
            }
        }, null)
    }

    // ---- Retry logic ----

    private fun retryOrGiveUp(reason: String) {
        retryCount++
        if (retryCount < MAX_RETRIES) {
            Timber.d("ACTION retry %d/%d: %s", retryCount, MAX_RETRIES, reason)
            handler.postDelayed({ tryExecutePendingAction() }, RETRY_DELAY_MS)
        } else {
            Timber.w("ACTION FAILED after %d retries: %s - %s",
                MAX_RETRIES, pendingAction?.description, reason)
            actionFailed("$reason after ${MAX_RETRIES} retries")
        }
    }

    // ---- Result handling ----

    private fun actionSuccess(description: String) {
        Timber.i("ACTION COMPLETE: %s", description)
        showNotification("Action executed", description)
        cleanup()
    }

    private fun actionFailed(reason: String) {
        val desc = pendingAction?.description ?: "Unknown action"
        Timber.e("ACTION FAILED: %s - %s", desc, reason)
        showNotification("Action failed", "$desc: $reason")
        cleanup()
    }

    private fun cleanup() {
        pendingAction = null
        retryCount = 0
        intentLaunched = false
        textPopulated = false
        readState = ReadState.WAITING_FOR_THREAD
        tryInProgress = false
        lastRetryTimeMs = 0L
        handler.removeCallbacksAndMessages(null)
    }

    // ---- App launch helpers ----

    /**
     * Known launcher activities for apps we automate.
     * Used as fallback when getLaunchIntentForPackage returns null
     * (common on Android 11+ even with <queries> declared).
     */
    private val knownLauncherActivities = mapOf(
        "com.google.android.apps.messaging" to "com.google.android.apps.messaging.ui.ConversationListActivity",
        "com.google.android.dialer" to "com.google.android.dialer.extensions.GoogleDialtactsActivity",
    )

    /**
     * Get a launch intent for a package, with fallback to known component names.
     * getLaunchIntentForPackage can return null on Android 11+ even with <queries>,
     * especially from AccessibilityService context. This tries the system resolver
     * first, then falls back to explicit component intents for known apps.
     */
    private fun getLaunchIntent(packageName: String, flags: Int): Intent? {
        // Try system resolver first
        val systemIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (systemIntent != null) {
            systemIntent.flags = flags
            Timber.d("LAUNCH: System resolved intent for %s", packageName)
            return systemIntent
        }

        // Fallback: explicit component intent for known apps
        val activityName = knownLauncherActivities[packageName]
        if (activityName != null) {
            Timber.i("LAUNCH: Using known activity fallback for %s -> %s", packageName, activityName)
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, activityName)
                this.flags = flags
            }
        }

        Timber.w("LAUNCH: No intent available for %s (not in known activities)", packageName)
        return null
    }

    // ---- Node search helpers ----

    /**
     * Use the system-level findAccessibilityNodeInfosByText() API.
     * This searches ALL semantics including Jetpack Compose semantics
     * labels that manual tree traversal misses. Returns the first
     * actionable (clickable or has ACTION_CLICK) match.
     */
    private fun findNodeBySystemSearch(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        if (matches.isNullOrEmpty()) return null

        Timber.d("System search for '%s' found %d matches", text, matches.size)

        // Prefer clickable matches, but accept any that support ACTION_CLICK
        for (node in matches) {
            val actions = node.actionList.map { it.id }
            Timber.d("  Match: desc='%s' text='%s' id=%s clickable=%s actions=%s",
                node.contentDescription, node.text,
                node.viewIdResourceName, node.isClickable, actions)

            if (node.isClickable || actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)) {
                // Recycle the others
                matches.filter { it != node }.forEach { try { it.recycle() } catch (_: Exception) {} }
                return node
            }
        }

        // No clickable match found — try clicking the first match anyway (Compose sometimes works)
        Timber.d("No clickable match, trying first result anyway")
        val first = matches.first()
        matches.drop(1).forEach { try { it.recycle() } catch (_: Exception) {} }
        return first
    }

    /**
     * Deep recursive search that does NOT require isClickable.
     * For Compose UIs where clickable state may not be exposed.
     * Searches content description at any depth.
     */
    private fun findNodeByDescriptionDeep(
        root: AccessibilityNodeInfo,
        text: String,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 15) return null

        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true)) {
            return root
        }
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
    private fun findNodeByContentDescription(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString()
        if (desc != null && desc.contains(text, ignoreCase = true) && root.isClickable) {
            return root
        }
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
    private fun findNodeByResourceId(
        root: AccessibilityNodeInfo,
        idSubstring: String,
    ): AccessibilityNodeInfo? {
        val resId = root.viewIdResourceName
        if (resId != null && resId.contains(idSubstring, ignoreCase = true) && root.isClickable) {
            return root
        }
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
    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val nodeText = root.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true) && root.isClickable) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findClickableByText(child, text)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Dump the accessibility tree for debugging.
     * Called when button search is about to fail, so we can see
     * what nodes actually exist.
     */
    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int) {
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

    // ---- Notifications ----

    private fun showNotification(title: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Timber.w(e, "Failed to show action notification")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dispatch Actions",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications for remote phone actions (calls, texts)"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
