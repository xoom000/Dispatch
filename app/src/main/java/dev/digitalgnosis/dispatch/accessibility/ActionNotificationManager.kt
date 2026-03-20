package dev.digitalgnosis.dispatch.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import timber.log.Timber

/**
 * Manages the notification channel and action result notifications
 * for DispatchAccessibilityService.
 */
internal class ActionNotificationManager(private val context: Context) {

    private val channelId = "dispatch_actions"

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Dispatch Actions",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications for remote phone actions (calls, texts)"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun showNotification(title: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Timber.w(e, "Failed to show action notification")
        }
    }
}
