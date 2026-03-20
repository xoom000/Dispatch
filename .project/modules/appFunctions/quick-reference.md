# AppFunctions — Quick Reference

**What:** Google's MCP for Android. Apps expose callable functions via annotations. AI agents discover and call them through the OS.
**Context7 ID:** `/androidx/androidx` (query: "AppFunction annotation AppFunctionService")
**Source:** https://developer.android.com/ai/appfunctions
**Version:** 1.0.0-alpha08 (March 11, 2026)
**Status:** Experimental preview. Provider side available NOW (no restrictions). Caller/agent side gated until Android 17.

## Gradle Dependencies

```kotlin
// build.gradle.kts (app)
plugins {
    id("com.google.devtools.ksp") // KSP required for annotation processing
}

dependencies {
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha08")
}
```

## Key Annotations

### @AppFunction
Marks a suspend function as an app function. Applied to functions inside an AppFunctionService subclass.

```kotlin
@kotlin.annotation.Retention(AnnotationRetention.BINARY)
@kotlin.annotation.Target(AnnotationTarget.FUNCTION)
annotation class AppFunction(
    val isEnabled: Boolean = true,
    val isDescribedByKDoc: Boolean = false  // If true, KDoc becomes the function description for AI discovery
)
```

### @AppFunctionSerializable
Marks data classes as serializable for AppFunctions request/response types.

```kotlin
@kotlin.annotation.Retention(AnnotationRetention.BINARY)
@kotlin.annotation.Target(AnnotationTarget.CLASS)
annotation class AppFunctionSerializable(
    val isDescribedByKDoc: Boolean = false
)
```

### @AppFunctionSchemaDefinition
Defines schema metadata for an app function (name, version, category).

```kotlin
annotation class AppFunctionSchemaDefinition(
    val name: String,
    val version: Int,
    val category: String
)
```

## AppFunctionService

Abstract class — your service extends this. Requires Android BAKLAVA (16+).

```kotlin
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
abstract class AppFunctionService : android.app.appfunctions.AppFunctionService() {
    abstract suspend fun executeFunction(
        request: ExecuteAppFunctionRequest,
        continuation: Continuation<ExecuteAppFunctionResponse?>
    ): Object?
}
```

## Implementation Pattern

### 1. Define data classes

```kotlin
@AppFunctionSerializable
data class MessageResult(
    val success: Boolean,
    val messageId: String,
    val error: String? = null
)

@AppFunctionSerializable
data class InboxMessage(
    val id: String,
    val from: String,
    val subject: String,
    val body: String,
    val timestamp: Long
)
```

### 2. Create your function class with @AppFunction methods

CRITICAL: First parameter of every @AppFunction MUST be `appFunctionContext: AppFunctionContext`.
Functions MUST be `suspend`. KDoc comments become the AI-readable descriptions when `isDescribedByKDoc = true`.

```kotlin
class DispatchFunctions(
    private val repository: DispatchRepository
) {

    /**
     * Send a voice dispatch message to a department.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     * @param message The message content to dispatch.
     * @param department The target department (e.g., "engineering", "boardroom").
     * @param priority Message priority: "low", "normal", or "urgent".
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun sendMessage(
        appFunctionContext: AppFunctionContext,
        message: String,
        department: String,
        priority: String = "normal"
    ): MessageResult {
        // Implementation calls File Bridge API
    }

    /**
     * Check unread messages in inbox.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun checkInbox(
        appFunctionContext: AppFunctionContext
    ): List<InboxMessage> {
        // Implementation calls cmail API
    }

    /**
     * Get system health status for all DG infrastructure.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getStatus(
        appFunctionContext: AppFunctionContext
    ): StatusResult {
        // Implementation calls health endpoints
    }
}
```

### 3. Declare in AndroidManifest.xml

```xml
<service
    android:name=".appfunctions.DispatchFunctionService"
    android:exported="true"
    android:permission="android.permission.EXECUTE_APP_FUNCTIONS">
    <intent-filter>
        <action android:name="androidx.appfunctions.AppFunctionService" />
    </intent-filter>
</service>
```

## How Discovery Works

- Functions indexed via AppSearch (on-device search index)
- KSP compiler generates the indexing metadata from your annotations
- AI agents (Gemini, etc.) query AppSearch to find available functions
- OS brokers the call — no direct app-to-app communication
- KDoc descriptions become the function descriptions the AI reads

## Three Artifacts

1. `androidx.appfunctions:appfunctions` — Core client APIs for managing (enable/disable) and interacting with (search/execute) AppFunctions
2. `androidx.appfunctions:appfunctions-service` — Service-side APIs to expose your app's functionalities as AppFunctions. Contains `@AppFunction` annotation
3. `androidx.appfunctions:appfunctions-compiler` — Required KSP compiler to generate necessary code for exposing AppFunctions

## Official Google Example (Note-Taking App)

```kotlin
class NoteFunctions(
    private val noteRepository: NoteRepository
) {
    /** Lists all available notes.
     * @param appFunctionContext The context in which the AppFunction is executed. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listNotes(appFunctionContext: AppFunctionContext): List<Note>? {
        return noteRepository.appNotes.ifEmpty { null }?.toList()
    }

    /** Adds a new note to the app.
     * @param appFunctionContext The context in which the AppFunction is executed.
     * @param title The title of the note.
     * @param content The note's content. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createNote(
        appFunctionContext: AppFunctionContext,
        title: String,
        content: String
    ): Note {
        return noteRepository.createNote(title, content)
    }
}

@AppFunctionSerializable(isDescribedByKDoc = true)
data class Note(
    /** The note's identifier */
    val id: Int,
    /** The note's title */
    val title: String,
    /** The note's content */
    val content: String
)
```
Source: https://github.com/android/snippets — AppFunctionsApiSnippets.kt

## Key Gotchas

- **AppFunctionContext is REQUIRED** — First parameter of every `@AppFunction` method must be `appFunctionContext: AppFunctionContext`. Without it, compilation fails
- **Functions must be suspend** — All `@AppFunction` methods are coroutine-based
- Requires `minSdk = 36` (Android 16 BAKLAVA) for the platform AppFunctionService
- The AndroidX library wraps the platform API — use `androidx.appfunctions.AppFunctionService`, not `android.app.appfunctions.AppFunctionService`
- KSP is REQUIRED — the compiler generates the function registry. Without it, functions won't be discoverable
- `isDescribedByKDoc = true` means your KDoc comments become the AI-readable description — write them like prompts
- Provider side: any app can expose functions NOW, no permission gate
- Caller side: `EXECUTE_APP_FUNCTIONS` permission is currently `signature|privileged` — only system apps (Gemini) can call. Expected to drop to `normal` in Android 17
- Data classes used as parameters/returns MUST be annotated with `@AppFunctionSerializable`

## Reference Docs

- Overview: https://developer.android.com/ai/appfunctions
- Jetpack releases: https://developer.android.com/jetpack/androidx/releases/appfunctions
- AppFunctionService API: https://developer.android.com/reference/android/app/appfunctions/AppFunctionService
- AppFunctionManager API: https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager
- Sample project: https://github.com/FilipFan/AppFunctionsPilot
- Deep dive (OreateAI): https://www.oreateai.com/blog/android-16-appfunctions-api-indepth-analysis-of-applicationlevel-mcp-support-empowering-ai-scenarios/90b7ebd6407ec0c29cb143e9067144a3
