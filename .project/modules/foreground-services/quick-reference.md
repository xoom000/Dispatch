# Foreground Services — Quick Reference

**What:** Long-running operations with visible notification. Required for media playback, location tracking, etc.
**Context7 ID:** `/websites/developer_android_guide` (query: "foreground service")
**Source:** https://developer.android.com/develop/background-work/services/foreground-services
**Version:** Android 14/15 requirements

## Permissions (AndroidManifest.xml)

```xml
<!-- Always required -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- Type-specific (Android 14+) — declare the ones you use -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Runtime permission (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Service Declaration

```xml
<service
    android:name=".playback.DispatchPlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

## Media3 MediaSessionService Pattern

```kotlin
class DispatchPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }
}
```

## Manual Foreground Service Pattern

```kotlin
class SyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        // Android 14+: must specify foreground service type
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        // Do work...
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syncing...")
            .setSmallIcon(R.drawable.ic_sync)
            .setOngoing(true)
            .build()
    }
}
```

## Foreground Service Types (Android 14+)

| Type | Use Case | Extra Permission |
|------|---------|-----------------|
| `mediaPlayback` | Audio/video playback | FOREGROUND_SERVICE_MEDIA_PLAYBACK |
| `dataSync` | Data transfer | FOREGROUND_SERVICE_DATA_SYNC |
| `location` | GPS tracking | FOREGROUND_SERVICE_LOCATION + ACCESS_*_LOCATION |
| `microphone` | Recording | FOREGROUND_SERVICE_MICROPHONE + RECORD_AUDIO |
| `camera` | Video capture | FOREGROUND_SERVICE_CAMERA + CAMERA |
| `mediaProjection` | Screen capture | FOREGROUND_SERVICE_MEDIA_PROJECTION |
| `connectedDevice` | Bluetooth/USB | FOREGROUND_SERVICE_CONNECTED_DEVICE |

## Key Gotchas

- **Android 14 type requirement** — Must specify `foregroundServiceType` in BOTH manifest AND `startForeground()` call. Mismatch = crash
- **Android 15 restrictions** — Apps can't start foreground services from background unless exempt (e.g., FCM high-priority message). Use WorkManager for deferrable work
- **Notification channel required** — Android 8+. Create in `Application.onCreate()` or before `startForeground()`
- **`ServiceCompat.startForeground()`** — Use this over `startForeground()` for backward-compatible type parameter
- **MediaSessionService** — Media3 handles foreground promotion automatically. Don't call `startForeground()` manually if using MediaSessionService
- **Timeout** — Android 15 enforces timeouts on some foreground service types. `dataSync` times out after ~6 hours
- **10-second window** — After `startForegroundService()`, you have ~10 seconds to call `startForeground()`. Miss it = ANR crash
