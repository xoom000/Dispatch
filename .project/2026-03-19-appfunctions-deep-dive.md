# Android AppFunctions Deep Dive — MCP for Mobile

**Date:** 2026-03-19
**Prepared by:** Research Engine
**Requested by:** Nigel (direct)
**Classification:** Strategic Technical Research
**Tags:** #android #appfunctions #agent #mcp #dispatch #strategic

---

## Executive Summary

**AppFunctions is Google's answer to MCP, but for Android.** It lets any app expose callable functions that AI agents can discover and execute — on-device, no server needed. Think of it as every Android app becoming an API that agents can talk to.

**The opportunity:** DG could build Dispatch (or a new product) as an agent app that orchestrates other apps on Nigel's phone. Or we expose our own apps' functions so Gemini and other agents can call them.

**The catch:** Right now, the `EXECUTE_APP_FUNCTIONS` permission that lets you BUILD an agent (caller) app is `signature|privileged` — meaning only system apps or Google-signed apps can get it on production devices. Third-party developers can **expose** functions freely, but **calling** other apps' functions is currently gated. Google says they're opening this up — Android 17 will "broaden these capabilities" — but today it's limited.

**Bottom line:** We should be building AppFunctions INTO our apps now (exposing side). The agent/caller side will open up, and when it does, we'll be ready.

---

## 1. What AppFunctions Actually Is

### The Concept

Every Android app can now declare "here's what I can do" in a structured, machine-readable way. An AI agent reads those declarations, picks the right function, and calls it.

**Analogy:** MCP lets AI tools call server-side functions. AppFunctions lets AI agents call app-side functions. Same pattern, different runtime.

### Two Sides of the Coin

| Role | What it does | Who can do it | Permission needed |
|------|-------------|---------------|-------------------|
| **Provider** (Tool) | Exposes functions for others to call | Any app | None (just annotations) |
| **Caller** (Agent) | Discovers and invokes other apps' functions | Currently system/privileged apps | `EXECUTE_APP_FUNCTIONS` |

**Important:** An app can always call its OWN functions without any special permission. The restriction is only for cross-app calling.

---

## 2. Architecture

### How Functions Get Exposed

```
Developer annotates Kotlin function with @AppFunction
        ↓
KSP compiler generates XML schema at build time
        ↓
Schema installed with APK
        ↓
Android OS indexes schema via AppSearch on:
  - App install/update
  - Device boot
        ↓
Functions discoverable via AppFunctionManager
```

### How Functions Get Called

```
Agent app queries AppSearch for available functions
        ↓
Gets AppFunctionStaticMetadata documents
        ↓
Identifies matching function by schema/capability
        ↓
Builds ExecuteAppFunctionRequest with parameters
        ↓
Calls AppFunctionManager.executeAppFunction()
        ↓
Android binds to target app's AppFunctionService
        ↓
Target app processes request, returns ExecuteAppFunctionResponse
        ↓
Agent receives structured GenericDocument result
```

### Security Layers

1. **BIND_APP_FUNCTION_SERVICE** — Only system processes can bind to an app's AppFunctionService. Third-party apps can't directly bind. This prevents malicious apps from poking at your exposed functions.
2. **EXECUTE_APP_FUNCTIONS** — Required for cross-app function calling. Currently `signature|privileged` protection level.
3. **AppSearch visibility** — Function metadata is only visible to packages that have visibility to the provider app (standard Android package visibility rules).

---

## 3. Building a Provider (Exposing Functions)

This is the side we can use TODAY. No restrictions.

### Dependencies (Gradle KTS)

```kotlin
dependencies {
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha08")
}
```

Requires KSP (Kotlin Symbol Processing) plugin.

### Three Library Components

- **appfunctions** — Core client APIs (for callers AND providers)
- **appfunctions-service** — Service-side APIs (for providers)
- **appfunctions-compiler** — KSP annotation processor (generates schema)

### Defining Functions

```kotlin
class DispatchFunctions {

    @AppFunction(isDescribedByKDoc = true)
    /** Send a voice message to a department */
    suspend fun sendMessage(
        appFunctionContext: AppFunctionContext,
        message: String,
        department: String,
        priority: String = "normal"
    ): MessageResult {
        // Implementation
    }

    @AppFunction(isDescribedByKDoc = true)
    /** Check inbox for unread messages */
    suspend fun checkInbox(
        appFunctionContext: AppFunctionContext
    ): List<InboxMessage>? {
        return inboxRepository.getUnread().ifEmpty { null }
    }
}
```

### Key Rules

- **Every function MUST have `appFunctionContext: AppFunctionContext` as first parameter**
- Functions should be `suspend` (coroutine-based) — `onExecuteFunction` runs on main thread
- KDoc comments become the function description that agents read
- `isDescribedByKDoc = true` tells the compiler to use KDoc as the schema description

### Serializable Data Types

```kotlin
@AppFunctionSerializable(isDescribedByKDoc = true)
data class MessageResult(
    /** Whether the message was delivered */
    val delivered: Boolean,
    /** The dispatch trace ID */
    val traceId: String,
    /** Timestamp of delivery */
    val timestamp: Long
)
```

### Supported Parameter Types

- Primitives: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`
- Collections: `List`, `Set`, `Map`
- Custom data classes annotated with `@AppFunctionSerializable`
- Nullable types (for optional parameters)
- Constrained values via `@AppFunctionIntValueConstraint` and `@AppFunctionStringValueConstraint`

### Manifest Declaration

Provider apps must declare the service:

```xml
<service
    android:name=".YourAppFunctionService"
    android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
    <intent-filter>
        <action android:name="android.app.appfunctions.APP_FUNCTION_SERVICE" />
    </intent-filter>
</service>
```

### Enable/Disable at Runtime

Functions can be toggled without redeploying:

```kotlin
val manager = AppFunctionManager(context)
manager.setAppFunctionEnabled(
    "functionIdentifier",
    AppFunctionState.DISABLED,  // or ENABLED
    executor,
    callback
)
```

---

## 4. Building an Agent (Calling Functions)

This is the gated side. Here's how it works architecturally, even though production access is currently restricted.

### Discovery Flow

```kotlin
// 1. Query AppSearch for available functions
//    AppFunctionStaticMetadata documents are indexed automatically
//    by the OS when apps are installed

// 2. Retrieve function metadata
val metadata = appSearchClient.search(
    "CreateNote",  // or search by schema type
    searchSpec
)

// 3. Parse AppFunctionStaticMetadata
//    Contains: functionIdentifier, parameter schemas, return type,
//    descriptions, package name
```

### Execution Flow

```kotlin
val manager = AppFunctionManager(context)

// Build request
val request = ExecuteAppFunctionRequest.Builder(
    targetPackage = "com.example.notes",
    functionIdentifier = "com.example.notes.createNote"
).setParameters(
    GenericDocument.Builder("params", "")
        .setPropertyString("title", "Meeting Notes")
        .setPropertyString("content", "Discuss Q2 roadmap")
        .build()
).build()

// Execute with cancellation support
manager.executeAppFunction(
    request,
    executor,
    cancellationSignal,
    object : OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> {
        override fun onResult(response: ExecuteAppFunctionResponse) {
            val result = response.resultDocument
            // Process structured result
        }
        override fun onError(error: AppFunctionException) {
            // Handle failure
        }
    }
)
```

### The Permission Problem (Current State)

On production Android 16 devices, `EXECUTE_APP_FUNCTIONS` is `signature|privileged`:

- **Normal third-party apps CANNOT get this permission**
- Only system apps (pre-installed) or apps signed with the platform key
- The AppFunctionsPilot sample project requires either:
  - Normal permission level (not current default) → direct install
  - Privileged level (current default) → root + `/system/priv-app` installation

**Workaround for development:** Use a `ContentProvider` fallback (the sample has a `USE_CONTENT_PROVIDER` flag), but this has limitations and isn't a production path.

### When This Opens Up

Google has explicitly stated:
- "Building experiences with a small set of app developers" (current)
- "Plan to share details later this year on how you can use AppFunctions" (2026)
- Android 17 will "broaden these capabilities to reach even more users, developers, and device manufacturers"

**My read:** The permission will likely be downgraded to `normal` or a new gating mechanism (like Play Store approval) will be introduced. Google wants this ecosystem to grow — keeping it locked to system apps defeats the purpose.

---

## 5. The Bigger Picture: Android's Agent Architecture

### Three Layers of Agent Integration

| Layer | What it is | Developer effort | Availability |
|-------|-----------|-----------------|--------------|
| **AppFunctions** | Structured function exposure | Medium (annotations + implementation) | Provider: NOW. Caller: restricted |
| **UI Automation** | Gemini taps/scrolls/types in apps | Zero (works on existing apps) | Google only, select devices |
| **Accessibility Services** | Custom accessibility automation | High (full service implementation) | Being locked down in Android 17 |

### Android 17 Accessibility Lockdown

Critical context: **Android 17 is blocking non-accessibility apps from using the Accessibility API for automation.** This closes the back door that many automation apps used. AppFunctions is Google's sanctioned replacement path.

This means:
- The old "build an accessibility service that controls other apps" approach is dying
- AppFunctions is the future-proof path for cross-app orchestration
- Google is funneling everyone toward their structured framework

---

## 6. What This Means for DG

### Immediate Opportunity: Expose Dispatch Functions

We can do this TODAY with no restrictions:

**Functions Dispatch could expose:**
- `sendMessage(message, department, priority)` → Send voice dispatch
- `checkInbox()` → Read unread messages
- `getStatus()` → System status check
- `queryRoute(routeNumber)` → Route 33 customer lookup
- `checkDeliveryGaps(days)` → Find customers who haven't ordered

**What this gets us:** Gemini (and future agents) can talk to Dispatch. "Hey Gemini, send a message to engineering that the build is ready" → Gemini calls Dispatch's `sendMessage` AppFunction.

### Medium-Term: Build the Agent Side

When Google opens `EXECUTE_APP_FUNCTIONS` to third parties:

**Dispatch becomes a hub that:**
- Discovers what apps are on the phone and what they can do
- Orchestrates multi-app workflows via voice
- "Check my calendar, then text Melanie the time of our dinner reservation" → Dispatch queries Calendar's AppFunctions, then Messages' AppFunctions

### Strategic Position

We'd be building **MCP for Android, but with a voice interface.** That's Dispatch's killer feature — it's already a voice-first agent. AppFunctions just gives it hands.

---

## 7. Technical Gotchas & Limitations

### Current Limitations

1. **Alpha quality** — Library is at 1.0.0-alpha08 (March 11, 2026). API surface is still changing. `AppFunctionManagerCompat` was renamed to `AppFunctionManager` in alpha08.
2. **Caller permission gated** — Can't build a production agent app yet on stock devices
3. **Android 16+ only** — No backport for older Android versions (Jetpack handles graceful degradation but functions just won't be available)
4. **Main thread constraint** — `onExecuteFunction` runs on main thread. All heavy work must be async.
5. **Schema indexing delay** — Functions are indexed on install/update/boot. No real-time schema updates.

### Known API Instabilities

From the release notes:
- alpha07 → alpha08: `AppFunctionManagerCompat` renamed to `AppFunctionManager`
- alpha06: Dropped "Compat" suffix from Service APIs
- alpha05: Added required field enforcement (breaking if you had optional fields that should have been required)
- Parcelable support is new as of alpha08

### Testing Support

- `AppFunctionTestRule` exists for Robolectric testing (added in alpha03)
- Unit testing of exposed functions is straightforward (they're just Kotlin suspend functions)
- Integration testing of the cross-app calling flow requires device/emulator with ADB flag enabled

### Device Flag Required for Development

Must enable:
```bash
adb shell aflags list | grep "enable_app_functions_schema_parser"
```

If this flag isn't available on your device/emulator, use the ContentProvider fallback.

---

## 8. Comparison: AppFunctions vs MCP

| Aspect | AppFunctions | MCP |
|--------|-------------|-----|
| Runtime | On-device (Android) | Server-side / local process |
| Discovery | AppSearch indexing | Manifest/tool listing |
| Transport | Android IPC (bound service) | stdio / HTTP SSE |
| Schema | XML generated from annotations | JSON Schema |
| Security | Android permissions + package visibility | Transport-level |
| Language | Kotlin/Java | Any (Python, TypeScript common) |
| State | Can be enabled/disabled at runtime | Server controls availability |
| Maturity | Alpha (1.0.0-alpha08) | Stable (widely adopted) |

**Key similarity:** Both are "here's what I can do, here's how to call me" protocols. The mental model is identical.

**Key difference:** AppFunctions is deeply integrated with the Android OS. Discovery happens through system-level indexing, not runtime negotiation.

---

## 9. Implementation Roadmap for DG

### Phase 1: NOW — Expose Functions in Dispatch (No restrictions)

1. Add AppFunctions dependencies to Dispatch's Gradle build
2. Define `@AppFunction` annotated functions for core Dispatch capabilities
3. Create `@AppFunctionSerializable` data classes for request/response types
4. Declare `AppFunctionService` in manifest
5. Test with Gemini on Pixel 9 (Android 16)

**Estimated effort:** 2-3 engineering sessions
**Risk:** Low (additive, doesn't change existing functionality)

### Phase 2: Q3 2026 — Monitor Permission Changes

- Watch Android 17 beta releases for `EXECUTE_APP_FUNCTIONS` permission changes
- Track Google I/O 2026 announcements (likely May/June)
- Prototype agent functionality on rooted dev device

### Phase 3: When Caller Permission Opens — Build the Agent

- Implement function discovery via AppSearch
- Build intent-matching logic (user voice → function selection)
- Wire Claude as the reasoning layer for function selection
- Ship Dispatch as a full local agent

---

## 10. Reference Links

### Official Documentation
- [AppFunctions Overview](https://developer.android.com/ai/appfunctions)
- [Jetpack Library Releases](https://developer.android.com/jetpack/androidx/releases/appfunctions)
- [AppFunctionService API Reference](https://developer.android.com/reference/android/app/appfunctions/AppFunctionService)
- [AppFunctionManager API Reference](https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager)

### Sample Code
- [AppFunctionsPilot (GitHub)](https://github.com/FilipFan/AppFunctionsPilot) — Reference implementation with Agent + Tool apps

### Analysis & Context
- [Google details AppFunctions](https://9to5google.com/2026/02/25/android-appfunctions-gemini/)
- [Android Developers Blog: The Intelligent OS](https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html)
- [In-Depth AppFunctions Analysis](https://www.oreateai.com/blog/android-16-appfunctions-api-indepth-analysis-of-applicationlevel-mcp-support-empowering-ai-scenarios/90b7ebd6407ec0c29cb143e9067144a3)
- [CommonsWare Analysis](https://commonsware.com/blog/2024/11/20/app-functions-android-16-dp1-musings.html)

### Related
- [MCP Specification](https://modelcontextprotocol.io/docs/getting-started/intro)
- [Android 16 Behavior Changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 17 Beta 1 Blog Post](https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html)

---

*Research Engine — Digital Gnosis*
*Confidence: High on architecture/API details (sourced from official docs). Medium on timeline predictions (based on Google's public statements). Low on permission changes (speculation based on ecosystem direction).*
