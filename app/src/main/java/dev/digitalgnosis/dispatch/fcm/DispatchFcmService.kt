package dev.digitalgnosis.dispatch.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.EntryPointAccessors
import dev.digitalgnosis.dispatch.accessibility.DispatchAccessibilityService
import dev.digitalgnosis.dispatch.accessibility.PhoneAction
import dev.digitalgnosis.dispatch.config.TokenManager
import dev.digitalgnosis.dispatch.data.DispatchMessage
import dev.digitalgnosis.dispatch.data.MessageRepository
import dev.digitalgnosis.dispatch.logging.FileLogTree
import dev.digitalgnosis.dispatch.ui.MainActivity
import timber.log.Timber

class DispatchFcmService : FirebaseMessagingService() {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)
    }

    private val messageRepository: MessageRepository by lazy { entryPoint.messageRepository() }
    private val tokenManager: TokenManager by lazy { entryPoint.tokenManager() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("New FCM token: %s", token)
        tokenManager.saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val receiveTime = System.currentTimeMillis()
        Timber.i("FCM RECEIVED at %d from: %s", receiveTime, message.from)

        val data = message.data

        // ---- Action routing (v3.1 — accessibility actions) ----
        // If type="action", route to AccessibilityService instead of audio pipeline.
        if (data["type"] == "action") {
            handleActionPayload(data, receiveTime)
            flushLogs()
            return
        }

        val sender = data["sender"] ?: "unknown"
        val messageText = data["message"] ?: run {
            Timber.w("FCM message has no 'message' field, ignoring. Data keys: %s", data.keys)
            return
        }
        val priority = data["priority"] ?: "normal"
        val timestamp = data["timestamp"] ?: ""
        val voice = data["voice"]

        // File attachment fields (v2.1 single + v2.6 multi)
        val fileUrl = data["file_url"]
        val fileName = data["file_name"]
        val fileSize = data["file_size"]?.toLongOrNull()
        // Multi-file: comma-separated lists
        val fileUrls = data["file_urls"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val fileNames = data["file_names"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val fileSizes = data["file_sizes"]?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

        // Thread context (v3.0 — thread-aware dispatch)
        val threadId = data["thread_id"]

        val totalFiles = if (fileUrls.isNotEmpty()) fileUrls.size else if (fileUrl != null) 1 else 0
        Timber.i("FCM payload: sender=%s, priority=%s, msgLen=%d, voice=%s, files=%d, thread=%s",
            sender, priority, messageText.length, voice ?: "none", totalFiles, threadId ?: "none")

        val dispatchMessage = DispatchMessage(
            sender = sender,
            message = messageText,
            priority = priority,
            timestamp = timestamp,
            voice = voice,
            fileUrl = fileUrl,
            fileName = fileName,
            fileSize = fileSize,
            fileUrls = fileUrls,
            fileNames = fileNames,
            fileSizes = fileSizes,
            threadId = threadId,
        )

        messageRepository.addMessage(dispatchMessage)

        // Start foreground service for reliable background audio playback.
        // FCM high-priority data messages are exempt from background start restrictions.
        // The service promotes to foreground immediately, holding the process alive
        // through the entire download + playback pipeline.
        val spokenText = "$sender says: $messageText"
        val resolvedVoice = voice ?: voiceForSender(sender)

        Timber.i("FCM -> starting AudioPlaybackService at +%dms: voice=%s",
            System.currentTimeMillis() - receiveTime, resolvedVoice)

        try {
            val serviceIntent = AudioPlaybackService.createIntent(
                context = this,
                text = spokenText,
                voice = resolvedVoice,
                sender = sender,
                message = messageText
            )
            startForegroundService(serviceIntent)
            Timber.i("FCM -> foreground service started in %dms",
                System.currentTimeMillis() - receiveTime)
        } catch (e: Exception) {
            Timber.e(e, "FCM -> FAILED to start AudioPlaybackService: %s", e.message)
        }

        val notifText = if (fileName != null) "$messageText [$fileName]" else messageText
        showNotification(sender, notifText)

        // Force flush logs so they're visible via SSH immediately
        flushLogs()
    }

    /**
     * Handle an action FCM payload. Routes to AccessibilityService for execution.
     * Actions are phone-control commands (call, text) that use UI automation.
     */
    private fun handleActionPayload(data: Map<String, String>, receiveTime: Long) {
        val action = PhoneAction.fromFcmData(data)
        if (action == null) {
            Timber.w("FCM action payload invalid. Keys: %s", data.keys)
            return
        }

        val sender = data["sender"] ?: "system"
        Timber.i("FCM ACTION at +%dms: %s (from %s)",
            System.currentTimeMillis() - receiveTime, action.description, sender)

        // Log to message repository so it shows in the app
        val dispatchMessage = DispatchMessage(
            sender = sender,
            message = "[Action] ${action.description}",
            priority = data["priority"] ?: "normal",
            timestamp = data["timestamp"] ?: "",
        )
        messageRepository.addMessage(dispatchMessage)

        if (!DispatchAccessibilityService.isEnabled()) {
            Timber.e("FCM ACTION BLOCKED: AccessibilityService not enabled. " +
                    "User must enable it in Settings > Accessibility > Dispatch")
            showNotification("Action blocked",
                "${action.description} — enable Dispatch in Accessibility settings")
            return
        }

        val accepted = DispatchAccessibilityService.executeAction(action)
        if (accepted) {
            Timber.i("FCM ACTION accepted by AccessibilityService: %s", action.description)
        } else {
            Timber.e("FCM ACTION rejected by AccessibilityService: %s", action.description)
            showNotification("Action failed", action.description)
        }
    }

    private fun flushLogs() {
        try {
            if (FileLogTree.isInitialized()) {
                FileLogTree.getInstance().flush()
            }
        } catch (e: Exception) {
            Timber.w(e, "FCM log flush failed")
        }
    }

    /**
     * Default voice mapping for senders when FCM payload doesn't include voice.
     * Mirrors the CLI-side mapping in dispatch config.py.
     */
    private fun voiceForSender(sender: String): String = when (sender.lowercase()) {
        "engineering" -> "am_michael"
        "watchman" -> "am_adam"
        "boardroom", "ceo" -> "am_eric"
        "dispatch" -> "am_puck"
        "aegis" -> "am_fenrir"
        "research" -> "bm_george"
        "hunter" -> "am_onyx"
        "alchemist" -> "am_liam"
        "prompt-engine" -> "am_echo"
        "trinity" -> "af_nova"
        "it" -> "am_adam"
        "cipher" -> "bm_lewis"
        "axiom" -> "bm_fable"
        else -> "am_michael"
    }

    private fun showNotification(sender: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // TODO: Replace with custom Dispatch icon once added to res/drawable
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Dispatch voice messages from DG agents"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dispatch_voice"
        private const val CHANNEL_NAME = "Dispatch Voice Messages"
    }
}
