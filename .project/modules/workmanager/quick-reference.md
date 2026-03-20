# WorkManager — Quick Reference

**What:** Schedule deferrable, guaranteed background work. Survives app kills and device reboots.
**Context7 ID:** `/websites/developer_android_guide` (query: "WorkManager")
**Source:** https://developer.android.com/guide/background/persistent
**Version:** 2.9.1

## Gradle Dependencies

```kotlin
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
```

## Define a Worker

```kotlin
// Simple Worker
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        // Do synchronous work
        return Result.success()   // or Result.failure() or Result.retry()
    }
}

// Coroutine Worker (preferred for async work)
class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val data = inputData.getString("file_path") ?: return Result.failure()
        // Do async work with coroutines
        return Result.success(workDataOf("upload_id" to "abc123"))
    }
}
```

## One-Time Work

```kotlin
val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
    .setInputData(workDataOf("file_path" to "/path/to/file"))
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .addTag("upload")
    .build()

WorkManager.getInstance(context).enqueue(uploadRequest)
```

## Periodic Work

```kotlin
val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
    repeatInterval = 1, TimeUnit.HOURS,
    flexInterval = 15, TimeUnit.MINUTES  // Can run within last 15 min of each hour
)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .build()
    )
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "sync_work",
    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
    syncRequest
)
```

## Chaining Work

```kotlin
WorkManager.getInstance(context)
    .beginWith(listOf(downloadWork, parseWork))  // Parallel
    .then(processWork)                            // Sequential after both complete
    .then(uploadWork)                             // Sequential
    .enqueue()
```

## Observe Work Status

```kotlin
// In ViewModel
WorkManager.getInstance(context)
    .getWorkInfoByIdLiveData(uploadRequest.id)
    .observe(lifecycleOwner) { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> {
                val uploadId = workInfo.outputData.getString("upload_id")
            }
            WorkInfo.State.FAILED -> { /* handle failure */ }
            WorkInfo.State.RUNNING -> { /* show progress */ }
            else -> {}
        }
    }
```

## Constraints

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)    // Any network
    .setRequiredNetworkType(NetworkType.UNMETERED)    // WiFi only
    .setRequiresCharging(true)                         // Plugged in
    .setRequiresBatteryNotLow(true)                   // Battery > 15%
    .setRequiresStorageNotLow(true)                   // Storage available
    .build()
```

## Key Gotchas

- **Minimum periodic interval is 15 minutes** — Android enforces this. Anything shorter gets rounded up
- **`CoroutineWorker` > `Worker`** — Use CoroutineWorker for async operations. Worker.doWork() runs on a background thread but blocks it
- **Unique work** — Use `enqueueUniqueWork()` / `enqueueUniquePeriodicWork()` to prevent duplicates
- **Input/Output data** — Limited to 10KB. Use for references (file paths, IDs), not bulk data
- **Work survives app kill** — That's the point. But it also means your worker must be self-contained (no in-memory state)
- **Expedited work** — Use `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` for time-sensitive one-time work
- **Foreground from Worker** — Call `setForeground(ForegroundInfo(...))` inside doWork() for long tasks that need a notification
