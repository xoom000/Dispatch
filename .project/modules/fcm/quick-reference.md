# Firebase Cloud Messaging (FCM) — Quick Reference

**What:** Push notification delivery from server to Android devices. Handles both notification and data messages.
**Context7 ID:** `/websites/firebase_google` (query: "FCM Android messaging")
**Source:** https://firebase.google.com/docs/cloud-messaging/android/client
**Version:** Firebase BOM 33.7.0

## Gradle Dependencies

```kotlin
// build.gradle.kts (project)
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// build.gradle.kts (app)
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
```

## AndroidManifest.xml

```xml
<service
    android:name=".messaging.DispatchMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

## FirebaseMessagingService

```kotlin
class DispatchMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed: $token")
        // MUST send to your server — token rotates on reinstall, wipe, or security events
        sendTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Data messages — always delivered here (foreground AND background)
        message.data.isNotEmpty().let {
            val command = message.data["command"]
            val payload = message.data["payload"]
            handleDataMessage(command, payload)
        }

        // Notification messages — only delivered here when app is in FOREGROUND
        // When app is in background, system tray handles it automatically
        message.notification?.let {
            showNotification(it.title, it.body)
        }
    }
}
```

## Get Current Token

```kotlin
FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (task.isSuccessful) {
        val token = task.result
        Log.d(TAG, "FCM token: $token")
    }
}
```

## Send from Server (HTTP v1 API)

```json
POST https://fcm.googleapis.com/v1/projects/{project-id}/messages:send
Authorization: Bearer {access-token}

{
    "message": {
        "token": "{device-token}",
        "data": {
            "command": "DISPATCH_VOICE",
            "audio_url": "https://..."
        }
    }
}
```

## Message Types

| Type | Foreground | Background | Killed |
|------|-----------|------------|--------|
| **Data** | onMessageReceived | onMessageReceived | onMessageReceived (on restart) |
| **Notification** | onMessageReceived | System tray | System tray |
| **Both** | onMessageReceived | Notification: tray, Data: onMessageReceived | Notification: tray |

## Key Gotchas

- **Token rotation is silent** — `onNewToken` fires on reinstall, wipe, or security compromise. If you don't POST the new token to your server, push delivery silently fails with "entity not found"
- **Data messages vs Notification messages** — Use data-only messages for reliability. Notification messages are handled differently in background
- **google-services.json** — Must be in `app/` directory. Firebase init fails silently without it
- **Crashlytics needs its own plugin** — Adding `firebase-crashlytics-ktx` without the `com.google.firebase.crashlytics` Gradle plugin causes launch crashes (DG gotcha: 2026-03-19)
- **Background execution limits** — Android 12+ limits background work from FCM. Use WorkManager for heavy processing triggered by push
- **Channel requirement** — Android 8+ requires notification channels. Create them in Application.onCreate()
