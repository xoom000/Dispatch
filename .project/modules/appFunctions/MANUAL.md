# AppFunctions Developer Manual

**Version:** 1.0.0-alpha08 (March 2026)
**Audience:** Android developers with Kotlin/Jetpack Compose experience who need to implement AppFunctions from scratch
**Status of the API:** Experimental preview — every alpha release has broken APIs; plan accordingly.

---

## Table of Contents

1. [What AppFunctions Is](#1-what-appfunctions-is)
2. [Prerequisites](#2-prerequisites)
3. [Gradle Setup](#3-gradle-setup)
4. [The @AppFunction Annotation](#4-the-appfunction-annotation)
5. [The @AppFunctionSerializable Annotation](#5-the-appfunctionserializable-annotation)
6. [AppFunctionContext](#6-appfunctioncontext)
7. [AppFunctionService — The Jetpack Wrapper](#7-appfunctionservice--the-jetpack-wrapper)
8. [AppFunctionConfiguration — Wiring Factories](#8-appfunctionconfiguration--wiring-factories)
9. [AndroidManifest.xml Declaration](#9-androidmanifestxml-declaration)
10. [How Discovery Works](#10-how-discovery-works)
11. [The Caller Side — AppFunctionManager](#11-the-caller-side--appfunctionmanager)
12. [Security Model](#12-security-model)
13. [Value Constraints](#13-value-constraints)
14. [URI Grants](#14-uri-grants)
15. [Deprecation Support](#15-deprecation-support)
16. [Complete Working Examples](#16-complete-working-examples)
17. [Testing with AppFunctionTestRule](#17-testing-with-appfunctiontestule)
18. [The Full Exception Hierarchy](#18-the-full-exception-hierarchy)
19. [Common Errors and Their Fixes](#19-common-errors-and-their-fixes)
20. [Version History](#20-version-history)
21. [Official Resources](#21-official-resources)

---

## 1. What AppFunctions Is

AppFunctions is an Android 16 (API 36) platform feature that lets your app expose specific pieces of functionality — as callable, self-describing functions — to other apps and to AI agents on the device. Think of it as the Android equivalent of the Model Context Protocol (MCP) used in server-side AI tooling, but running entirely on-device.

The most prominent consumer of AppFunctions today is the Gemini app. When a user says "create a note called Meeting Notes," Gemini discovers which installed apps have a compatible `createNote` function, picks the right one, and invokes it without the user ever leaving the Gemini interface.

**What AppFunctions is not:**

- It is not a way to share UI between apps (that is handled by intents, Activities, or widgets).
- It is not server-side. All function execution happens locally on the device.
- It is not open to any caller. The caller must hold the `EXECUTE_APP_FUNCTIONS` permission.

**How it works at a high level:**

1. You annotate Kotlin functions in your app with `@AppFunction`.
2. The KSP compiler (`appfunctions-compiler`) generates an XML schema at build time that describes those functions — their names, parameter types, and descriptions.
3. When your app is installed, the system reads that XML and indexes the function metadata into **AppSearch** (Android's on-device search engine).
4. Caller apps (agents, Gemini) query AppSearch to discover available functions.
5. The caller builds an `ExecuteAppFunctionRequest` and calls `AppFunctionManager.executeAppFunction()`.
6. The platform routes the call to your `AppFunctionService`, which receives the request and invokes your implementation.

---

## 2. Prerequisites

| Requirement | Value |
|---|---|
| Minimum SDK (`minSdk`) | 36 (Android 16 / "Baklava") |
| Kotlin version | 2.x recommended (tested with 2.1.21) |
| KSP version | Must match Kotlin version — e.g., `2.1.21-2.0.1` |
| Jetpack AppFunctions version | `1.0.0-alpha08` (latest as of March 2026) |
| Build target | Must compile against API 36 |
| Java compatibility | Java 11 (class file version 55); desugaring may be required |
| Android Gradle Plugin | 8.x (tested with 8.11.0) |

**On-device requirement:** The device flag `enable_app_functions_schema_parser` must be enabled. On development devices you can verify this via `adb shell`. On production Android 16 devices it is enabled by default.

**Permission for callers:** The calling app needs `android.permission.EXECUTE_APP_FUNCTIONS`. On some devices this is a "privileged" permission, which means only system or pre-installed apps can hold it. On standard Android 16 consumer devices it is a "normal" permission grantable via the manifest. Always check the protection level on your target device with:

```
adb shell pm list permissions -f | grep -A 5 "EXECUTE_APP_FUNCTIONS"
```

---

## 3. Gradle Setup

### 3.1 Version Catalog (libs.versions.toml)

```toml
[versions]
kotlin = "2.1.21"
ksp = "2.1.21-2.0.1"
appfunctions = "1.0.0-alpha08"

[libraries]
appfunctions-core = { module = "androidx.appfunctions:appfunctions", version.ref = "appfunctions" }
appfunctions-service = { module = "androidx.appfunctions:appfunctions-service", version.ref = "appfunctions" }
appfunctions-compiler = { module = "androidx.appfunctions:appfunctions-compiler", version.ref = "appfunctions" }
appfunctions-testing = { module = "androidx.appfunctions:appfunctions-testing", version.ref = "appfunctions" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 3.2 Root build.gradle.kts (Plugin Declarations)

```kotlin
plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
```

### 3.3 Module-Level build.gradle.kts

```kotlin
plugins {
    id("com.android.application") // or "com.android.library"
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Core client API — callers and providers both need this
    implementation(libs.appfunctions.core)

    // Service-side API — only the provider app needs this
    implementation(libs.appfunctions.service)

    // KSP compiler — generates schema XML and wiring code at build time
    ksp(libs.appfunctions.compiler)

    // Testing support (Robolectric) — test scope only
    testImplementation(libs.appfunctions.testing)
}
```

### 3.4 KSP Compiler Options

The `appfunctions-compiler` exposes two KSP arguments you configure in your module's `build.gradle.kts`:

```kotlin
ksp {
    // Required: tells the compiler to aggregate all @AppFunction annotations
    // across the module into a single output. Must be true for production builds.
    arg("appfunctions:aggregateAppFunctions", "true")

    // Optional: generates legacy metadata format for devices using the older
    // AppSearch indexer. Set to true if you need to support the AppFunctionsPilot
    // content-provider fallback path, or if the platform's schema parser flag
    // is not guaranteed to be enabled.
    arg("appfunctions:generateMetadataFromSchema", "true")
}
```

**What these options do:**

- `aggregateAppFunctions=true` — Without this, each class with `@AppFunction` methods generates its own partial schema file. The aggregation step merges them all into the final XML that the system reads. You almost always want this enabled.
- `generateMetadataFromSchema=true` — The new AppSearch-based indexer reads a compact binary schema. The legacy indexer reads a more verbose XML format. Setting this to `true` causes the compiler to also emit the legacy format, which you need on devices where `enable_app_functions_schema_parser` is not enabled.

### 3.5 The Three Artifacts — What Each Does

| Artifact | Purpose | Who needs it |
|---|---|---|
| `appfunctions` | `AppFunctionManager`, `AppFunctionSearchSpec`, `ExecuteAppFunctionRequest/Response`, all exception types, `AppFunctionData`, `AppFunctionSerializable` annotation | Both caller and provider apps |
| `appfunctions-service` | `@AppFunction` annotation (the service-side version), `AppFunctionService`, `AppFunctionConfiguration` | Provider app only |
| `appfunctions-compiler` | KSP annotation processor that generates XML schema and factory wiring | Provider app only (build-time, `ksp` configuration) |
| `appfunctions-testing` | `AppFunctionTestRule` for Robolectric tests | Test scope only |

---

## 4. The @AppFunction Annotation

`@AppFunction` lives in the `androidx.appfunctions.service` package. It is applied to functions (methods) inside ordinary Kotlin classes. It marks those functions as AppFunctions that the system can discover and invoke.

### 4.1 Full Signature

```kotlin
package androidx.appfunctions.service

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class AppFunction(
    val isEnabled: Boolean = true,
    val isDescribedByKDoc: Boolean = false,
)
```

### 4.2 Parameters

**`isEnabled: Boolean` (default: `true`)**

Controls whether this function appears in discovery results by default. When `false`, the function is compiled into the schema but flagged as disabled. The runtime enabled/disabled state can be changed at runtime using `AppFunctionManager.setAppFunctionEnabled()`. Use `isEnabled = false` when you want to ship a function but gate it behind a feature flag or roll it out gradually.

**`isDescribedByKDoc: Boolean` (default: `false`)**

When `true`, the compiler reads the KDoc comment attached to the function and embeds its text as the function's natural-language description in the schema. AI agents use this description to decide whether and how to call the function. This is the primary way you communicate function intent to Gemini and other assistants.

Rules for KDoc when `isDescribedByKDoc = true`:

- The top-level KDoc paragraph becomes the function description.
- `@param appFunctionContext` is silently ignored (it is never exposed to callers).
- Each other `@param name description` becomes the description of that parameter.
- The `@return` tag is ignored (return type descriptions come from `@AppFunctionSerializable` KDoc).

```kotlin
/**
 * Sends a message to a conversation.
 *
 * @param appFunctionContext The execution context. Do not describe this to users.
 * @param conversationId The unique identifier of the conversation.
 * @param text The message body to send.
 */
@AppFunction(isEnabled = true, isDescribedByKDoc = true)
suspend fun sendMessage(
    appFunctionContext: AppFunctionContext,
    conversationId: String,
    text: String,
): SentMessage {
    // implementation
}
```

### 4.3 Supported Parameter and Return Types

The following types are supported natively as function parameters and return types:

| Kotlin Type | Notes |
|---|---|
| `String`, `String?` | Nullable makes the parameter optional |
| `Int`, `Int?` | |
| `Long`, `Long?` | |
| `Float`, `Float?` | |
| `Double`, `Double?` | |
| `Boolean`, `Boolean?` | |
| `ByteArray`, `ByteArray?` | |
| `IntArray`, `BooleanArray`, etc. | Primitive arrays |
| `List<String>`, `List<Int>`, etc. | Lists of primitives |
| Any `@AppFunctionSerializable` class | Custom data objects |
| `List<YourSerializableClass>` | Lists of custom objects |
| `PendingIntent` | As of alpha06, represented as a `Parcelable` |
| Any `Parcelable` | As of alpha08 |
| `List<Parcelable>` | As of alpha08 |
| `AppFunctionUriGrant` | Special type for URI permissions |

**Nullable parameters are optional.** If you want a parameter to be optional from the caller's perspective, make it nullable (`String?`). The caller can omit it entirely and it arrives as `null`. Non-nullable parameters are required — the system will reject a request that omits them.

**Return types:** Functions can return `Unit` (no result), any supported primitive, any `@AppFunctionSerializable` class, or `null` (nullable return). A nullable return type allows the function to signal "no result found" without throwing an exception.

### 4.4 The Function Must Be Suspend

Every `@AppFunction` function must be declared `suspend`. The `AppFunctionService` base class calls them from a coroutine scope. Non-suspend functions will cause a compile-time error from the KSP compiler.

### 4.5 The First Parameter Must Be AppFunctionContext

The first parameter of every `@AppFunction` function must be `AppFunctionContext`. This is enforced at compile time. The parameter name does not matter, but the type must be exactly `androidx.appfunctions.AppFunctionContext`. See [Section 6](#6-appfunctioncontext) for details.

---

## 5. The @AppFunctionSerializable Annotation

`@AppFunctionSerializable` is in the `androidx.appfunctions` package (the core library, not the service library). It marks data classes as serializable so they can be used as parameter types or return types in `@AppFunction` functions.

### 5.1 Full Signature

```kotlin
package androidx.appfunctions

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class AppFunctionSerializable(
    val isDescribedByKDoc: Boolean = false,
)
```

### 5.2 Parameter

**`isDescribedByKDoc: Boolean` (default: `false`)**

When `true`, the compiler reads KDoc on the class and on each property. Property-level KDoc (written as `/** The property description. */` before the property declaration in the primary constructor or body) becomes the description for that field in the schema.

### 5.3 Rules for Annotated Classes

1. The class must be a Kotlin `data class`.
2. All properties must use types supported by `@AppFunction` (see Section 4.3).
3. Properties must be declared in the primary constructor.
4. The class can be used inside another `@AppFunctionSerializable` class (nesting is supported).
5. Generic `@AppFunctionSerializable` classes are supported (e.g., `data class Page<T : @AppFunctionSerializable>(val items: List<T>)`).
6. The compiler generates serialization/deserialization code automatically — you do not write any `AppFunctionData` construction code manually.

### 5.4 Example

```kotlin
/**
 * A message in the Dispatch messaging app.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DispatchMessage(
    /** The unique identifier of this message. */
    val id: String,
    /** The display name of the sender. */
    val senderName: String,
    /** The message body text. */
    val body: String,
    /** The Unix timestamp (milliseconds) when this message was sent. */
    val sentAtMs: Long,
    /** Whether this message has been read by the recipient. */
    val isRead: Boolean,
)
```

---

## 6. AppFunctionContext

`AppFunctionContext` is an interface in `androidx.appfunctions`. It is the first parameter of every `@AppFunction` function. Its only current member is `context: android.content.Context`, which is the Android `Context` of your `AppFunctionService`.

### 6.1 Full Interface Definition

```kotlin
interface AppFunctionContext {
    val context: android.content.Context
}
```

### 6.2 What It Gives You

- Access to `Context` for database queries, repository calls, content resolver access, or any Android API that needs a `Context`.
- The service's `Context` — not an Activity context — so you cannot show dialogs or start foreground-only operations from here without additional coordination.

### 6.3 What It Does Not Give You

- The caller's identity. The caller package name and signing info are available to the `AppFunctionService.onExecuteFunction()` override if you need them, but they are not propagated into `AppFunctionContext` by default. If your implementation needs to know who is calling, you must thread that information through yourself.

### 6.4 Usage

```kotlin
@AppFunction(isDescribedByKDoc = true)
suspend fun listConversations(
    appFunctionContext: AppFunctionContext,
): List<Conversation> {
    val db = Room.databaseBuilder(
        appFunctionContext.context,
        AppDatabase::class.java,
        "dispatch-db"
    ).build()
    return db.conversationDao().getAll()
}
```

In practice, your class will already hold a reference to a repository injected at construction time (via Hilt or manual DI). The `AppFunctionContext` is there for cases where you genuinely need Android `Context` access inside the function body.

---

## 7. AppFunctionService — The Jetpack Wrapper

`AppFunctionService` (in `androidx.appfunctions`) is an abstract class that extends the platform's `android.app.appfunctions.AppFunctionService`. It acts as the glue between the Android framework's AIDL-based invocation mechanism and your Kotlin coroutine-based function implementations.

### 7.1 Full Class Signature

```kotlin
@RequiresApi(Build.VERSION_CODES.BAKLAVA) // API 36
abstract class AppFunctionService : android.app.appfunctions.AppFunctionService() {

    // You implement this. It receives the Jetpack-wrapped request and returns
    // a Jetpack-wrapped response. It runs on the main thread, so launch
    // coroutines to do real work.
    @MainThread
    abstract suspend fun executeFunction(
        request: ExecuteAppFunctionRequest,
    ): ExecuteAppFunctionResponse

    // The platform calls this. The Jetpack wrapper implements it and delegates
    // to executeFunction() above. You do NOT override this.
    final override fun onExecuteFunction(
        request: android.app.appfunctions.ExecuteAppFunctionRequest,
        callingPackage: String,
        signingInfo: android.content.pm.SigningInfo,
        cancellationSignal: android.os.CancellationSignal,
        callback: android.os.OutcomeReceiver<
            android.app.appfunctions.ExecuteAppFunctionResponse,
            android.app.appfunctions.AppFunctionException>
    )
}
```

### 7.2 The KSP-Generated Subclass

You do not extend `AppFunctionService` directly. The KSP compiler (`appfunctions-compiler`) generates a concrete subclass of `AppFunctionService` for you during the build. This generated class:

1. Overrides `executeFunction()` with a giant `when` block that matches the incoming `functionIdentifier` string to the correct `@AppFunction`-annotated method in your classes.
2. Deserializes the incoming `AppFunctionData` into the correct typed parameters.
3. Calls your annotated function.
4. Serializes the return value back into an `AppFunctionData` response.

You declare your class in the manifest (see Section 9). The generated class's fully-qualified name is deterministic — it follows the naming convention `<YourPackageName>.AppFunctionServiceImpl` or similar depending on your setup.

### 7.3 What You Actually Write

You write the classes that contain `@AppFunction` methods. These classes do not extend anything AppFunctions-specific. They are plain Kotlin classes.

```kotlin
// This is NOT an AppFunctionService. It's just a class with annotated methods.
class DispatchFunctions(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
) {
    @AppFunction(isDescribedByKDoc = true)
    suspend fun sendMessage(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
        text: String,
    ): DispatchMessage {
        return messageRepository.send(conversationId, text)
    }

    @AppFunction(isDescribedByKDoc = true)
    suspend fun listConversations(
        appFunctionContext: AppFunctionContext,
    ): List<Conversation>? {
        return conversationRepository.getAll().ifEmpty { null }
    }
}
```

### 7.4 Service Lifecycle

The platform binds to your `AppFunctionService` when it receives a function execution request. The service is an ordinary Android `Service` and follows the normal bound-service lifecycle. The Jetpack wrapper handles all coroutine launching internally — `executeFunction()` is a suspend function called from a coroutine scope managed by the service.

---

## 8. AppFunctionConfiguration — Wiring Factories

The KSP-generated code needs to know how to construct your classes that contain `@AppFunction` methods. This is done through `AppFunctionConfiguration`, which you provide by implementing `AppFunctionConfiguration.Provider` on your `Application` class.

### 8.1 Why This Is Needed

The generated service code needs to instantiate `DispatchFunctions` (for example) at runtime. It cannot use constructor injection directly — it needs a factory. `AppFunctionConfiguration` is where you register factories for each enclosing class.

### 8.2 Example with Manual DI

```kotlin
class DispatchApplication : Application(), AppFunctionConfiguration.Provider {

    // Your existing DI objects
    private val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(applicationContext)
    }
    private val conversationRepository: ConversationRepository by lazy {
        ConversationRepositoryImpl(applicationContext)
    }

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(DispatchFunctions::class.java) {
                DispatchFunctions(messageRepository, conversationRepository)
            }
            .build()
}
```

### 8.3 Example with Hilt

With Hilt, you typically cannot implement `AppFunctionConfiguration.Provider` directly on your `@HiltAndroidApp`-annotated `Application` subclass because Hilt generates the Application subclass. The recommended approach is to use an `EntryPoint`:

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppFunctionConfigEntryPoint {
    fun messageRepository(): MessageRepository
    fun conversationRepository(): ConversationRepository
}

@HiltAndroidApp
class DispatchApplication : Application(), AppFunctionConfiguration.Provider {

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() {
            val entryPoint = EntryPointAccessors.fromApplication(
                this,
                AppFunctionConfigEntryPoint::class.java
            )
            return AppFunctionConfiguration.Builder()
                .addEnclosingClassFactory(DispatchFunctions::class.java) {
                    DispatchFunctions(
                        entryPoint.messageRepository(),
                        entryPoint.conversationRepository()
                    )
                }
                .build()
        }
}
```

### 8.4 AppFunctionConfiguration.Builder API

```kotlin
class AppFunctionConfiguration private constructor(
    val enclosingClassFactories: Map<Class<*>, () -> Any>
) {
    class Builder {
        fun <T> addEnclosingClassFactory(
            enclosingClass: Class<T>,
            factory: () -> T,
        ): Builder

        fun build(): AppFunctionConfiguration
    }

    interface Provider {
        val appFunctionConfiguration: AppFunctionConfiguration
    }
}
```

---

## 9. AndroidManifest.xml Declaration

### 9.1 Provider App (the app exposing functions)

Your app needs two things in the manifest:

1. A `<service>` declaration for the KSP-generated `AppFunctionService` subclass.
2. Optionally, an `<application>` property pointing to an `app_metadata.xml` resource that describes your app to agents.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".DispatchApplication"
        ... >

        <!-- Optional: natural language description of your app for AI agents -->
        <property
            android:name="android.app.appfunctions.app_metadata"
            android:resource="@xml/app_metadata" />

        <!--
            The service declaration for the KSP-generated AppFunctionService.
            android:name: The generated class. The exact name depends on your package
                          and module structure. Check your build output under
                          build/generated/ksp/ to find the generated class name.
            android:permission: REQUIRED. Must be BIND_APP_FUNCTION_SERVICE.
                                Without this, any app could bind to your service.
            exported: Must be false for security — only the system should bind to this.
        -->
        <service
            android:name=".AppFunctionServiceImpl"
            android:exported="false"
            android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
            <intent-filter>
                <action android:name="android.app.appfunctions.AppFunctionService" />
            </intent-filter>
        </service>

    </application>

</manifest>
```

**Critical security note:** `android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"` restricts binding to the system only. If you omit this, any installed app can bind to your service and invoke your functions directly, bypassing the permission checks enforced by the platform. Never omit this attribute.

### 9.2 The app_metadata.xml Resource

Create `res/xml/app_metadata.xml` in your provider app:

```xml
<?xml version="1.0" encoding="utf-8"?>
<AppFunctionAppMetadata
    xmlns:appfn="http://schemas.android.com/apk/res-auto"
    appfn:description="Dispatch is a messaging app for dispatch professionals."
    appfn:displayDescription="Send and receive field communications through Dispatch." />
```

- `appfn:description` — Text shown to AI agents making decisions about which app to use.
- `appfn:displayDescription` — Text potentially shown to users in agent-facing UI.

### 9.3 Caller App (the app invoking functions)

The caller needs the permission declared in its manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.EXECUTE_APP_FUNCTIONS" />

    <!-- If your caller app queries functions from a specific package, declare visibility -->
    <queries>
        <package android:name="com.example.dispatchapp" />
    </queries>

    <application ... >
        <!-- your app's content -->
    </application>

</manifest>
```

---

## 10. How Discovery Works

Understanding discovery is important because it explains why your functions may not be immediately available after installation, and why testing requires specific setup.

### 10.1 The Indexing Pipeline

1. **Build time:** The KSP compiler reads all `@AppFunction` and `@AppFunctionSerializable` annotations and generates:
   - An XML file (typically at `res/xml/app_functions.xml` or similar) containing the full schema for all functions.
   - Kotlin source files that wire the schema to your implementations.

2. **Install time:** When your app is installed (or updated), the Android package manager triggers the AppSearch indexing pipeline.

3. **Indexing:** The system reads your app's XML schema and creates `AppFunctionStaticMetadata` documents in the AppSearch database. Each document contains the function's identifier, schema information, parameter descriptions, and enabled state.

4. **Discovery:** Callers query AppSearch using `AppFunctionManager.observeAppFunctions()`. They pass an `AppFunctionSearchSpec` that can filter by package name, schema category, schema name, or minimum schema version. The result is a `Flow<List<AppFunctionPackageMetadata>>` that emits whenever the available functions change (e.g., after your app is updated).

### 10.2 The functionIdentifier

Each AppFunction gets a stable, generated string identifier. You do not assign this yourself — the KSP compiler derives it from the class name, function name, and package. This identifier is what callers put in `ExecuteAppFunctionRequest` to invoke a specific function. You discover the identifier by querying `AppFunctionManager.observeAppFunctions()` and reading `AppFunctionMetadata.id`.

### 10.3 Enable/Disable State

Each function has an enabled/disabled state in AppSearch. The default state is determined by the `isEnabled` parameter on `@AppFunction`. Your app can change this at runtime:

```kotlin
// In your provider app, toggle a function on or off
val manager = AppFunctionManager.getInstance(context) ?: return
manager.setAppFunctionEnabled(
    functionId = "com.example.dispatch.DispatchFunctions#sendMessage",
    newEnabledState = AppFunctionManager.APP_FUNCTION_STATE_DISABLED,
)
```

State constants:
- `APP_FUNCTION_STATE_DEFAULT` (0) — returns to the `isEnabled` value from the annotation.
- `APP_FUNCTION_STATE_ENABLED` (1) — explicitly enabled regardless of the annotation default.
- `APP_FUNCTION_STATE_DISABLED` (2) — explicitly disabled.

Disabled functions are not returned in discovery results by default.

---

## 11. The Caller Side — AppFunctionManager

`AppFunctionManager` is the entry point for apps that want to discover and invoke AppFunctions in other apps. It lives in the `androidx.appfunctions` package.

### 11.1 Getting an Instance

`AppFunctionManager.getInstance()` returns `null` if the device does not support AppFunctions (i.e., below Android 16). Always null-check the result.

```kotlin
val manager = AppFunctionManager.getInstance(context)
    ?: throw UnsupportedOperationException("AppFunctions not supported on this device")
```

### 11.2 Discovering Functions

```kotlin
// Search all packages for all functions
val allFunctions = AppFunctionSearchSpec()

// Search a specific package
val dispatchFunctions = AppFunctionSearchSpec(
    packageNames = setOf("com.example.dispatchapp")
)

// Search by schema category and name (for predefined schemas)
val createNoteFunctions = AppFunctionSearchSpec(
    schemaCategory = "notes",
    schemaName = "createNote",
    minSchemaVersion = 1,
)

manager.observeAppFunctions(dispatchFunctions)
    .onEach { packageList: List<AppFunctionPackageMetadata> ->
        packageList.forEach { packageMetadata ->
            val packageName = packageMetadata.packageName
            val functions = packageMetadata.appFunctions // List<AppFunctionMetadata>

            // Get app-level metadata (description, display name)
            val appMetadata = packageMetadata.resolveAppFunctionAppMetadata(context)

            functions.forEach { fnMetadata ->
                println("Function: ${fnMetadata.id}")
                println("Description: ${fnMetadata.description}")
                println("Enabled: ${fnMetadata.isEnabled}")
                println("Deprecated: ${fnMetadata.deprecation?.message}")
            }
        }
    }
    .launchIn(lifecycleScope)
```

### 11.3 AppFunctionSearchSpec Parameters

```kotlin
class AppFunctionSearchSpec(
    val packageNames: Set<String>? = null,       // null = all packages
    val schemaCategory: String? = null,           // null = all categories
    val schemaName: String? = null,               // null = all schema names
    val minSchemaVersion: Int = 0,                // minimum version for predefined schemas
)
```

### 11.4 Executing a Function

To execute a function you need the target package name and the function identifier (both from `AppFunctionMetadata`).

```kotlin
// Build the request
val request = ExecuteAppFunctionRequest(
    targetPackageName = "com.example.dispatchapp",
    functionIdentifier = fnMetadata.id,
    functionParameters = buildFunctionParams(fnMetadata),
)

// Execute (suspend call — must be in a coroutine)
val response: ExecuteAppFunctionResponse = manager.executeAppFunction(request)

when (response) {
    is ExecuteAppFunctionResponse.Success -> {
        // Deserialize using the generated data class
        val message = response.returnValue.deserialize(DispatchMessage::class.java)
        println("Sent: ${message.id}")
    }
    is ExecuteAppFunctionResponse.Error -> {
        when (val error = response.error) {
            is AppFunctionInvalidArgumentException -> handleBadArgs(error)
            is AppFunctionDisabledException -> promptUserToEnableFeature()
            is AppFunctionDeniedException -> handlePermissionDenied()
            else -> log("Unexpected error: ${error.errorMessage}")
        }
    }
}
```

### 11.5 Building Function Parameters Manually

When building `AppFunctionData` manually (e.g., when you are the caller and do not have access to the provider's generated serialization code), use `AppFunctionData.Builder` with the function's parameter metadata:

```kotlin
fun buildFunctionParams(
    fnMetadata: AppFunctionMetadata,
    args: Map<String, Any>,
): AppFunctionData {
    val builder = AppFunctionData.Builder(
        fnMetadata.parameters,
        fnMetadata.components,
    )
    // Set each argument by key (parameter name) and type
    builder.setString("conversationId", args["conversationId"] as String)
    builder.setString("text", args["text"] as String)
    return builder.build()
}
```

### 11.6 Reading a Function Response

The `ExecuteAppFunctionResponse.Success.returnValue` is an `AppFunctionData`. You can read it in two ways:

**Deserialization (recommended if you have access to the data class):**

```kotlin
val message: DispatchMessage = response.returnValue.deserialize(DispatchMessage::class.java)
```

**Manual key access:**

```kotlin
val id: String? = response.returnValue.getString("id")
val senderName: String? = response.returnValue.getString("senderName")
val body: String? = response.returnValue.getString("body")
val sentAtMs: Long = response.returnValue.getLong("sentAtMs")
```

### 11.7 Checking Enabled State

```kotlin
// Check your own function
val isEnabled: Boolean = manager.isAppFunctionEnabled(functionId = "...")

// Check another app's function (requires EXECUTE_APP_FUNCTIONS permission)
val isEnabled: Boolean = manager.isAppFunctionEnabled(
    packageName = "com.example.dispatchapp",
    functionId = "...",
)
```

### 11.8 AppFunctionData — Full Type Support

`AppFunctionData` is the container for all function inputs and outputs. It supports the following typed accessors:

| Type | Getter | Setter |
|---|---|---|
| `String?` | `getString(key)` | `setString(key, value)` |
| `List<String>?` | `getStringList(key)` | `setStringList(key, value)` |
| `Int` | `getInt(key)` / `getInt(key, default)` | `setInt(key, value)` |
| `IntArray?` | `getIntArray(key)` | `setIntArray(key, value)` |
| `Long` | `getLong(key)` / `getLong(key, default)` | `setLong(key, value)` |
| `LongArray?` | `getLongArray(key)` | `setLongArray(key, value)` |
| `Float` | `getFloat(key)` / `getFloat(key, default)` | `setFloat(key, value)` |
| `FloatArray?` | `getFloatArray(key)` | `setFloatArray(key, value)` |
| `Double` | `getDouble(key)` / `getDouble(key, default)` | `setDouble(key, value)` |
| `DoubleArray?` | `getDoubleArray(key)` | `setDoubleArray(key, value)` |
| `Boolean` | `getBoolean(key)` / `getBoolean(key, default)` | `setBoolean(key, value)` |
| `BooleanArray?` | `getBooleanArray(key)` | `setBooleanArray(key, value)` |
| `ByteArray?` | `getByteArray(key)` | `setByteArray(key, value)` |
| `AppFunctionData?` | `getAppFunctionData(key)` | `setAppFunctionData(key, value)` |
| `List<AppFunctionData>?` | `getAppFunctionDataList(key)` | `setAppFunctionDataList(key, value)` |
| `T : Parcelable` | `getParcelable<T>(key)` | `setParcelable(key, value)` |
| `List<T : Parcelable>?` | `getParcelableList<T>(key)` | `setParcelableList(key, value, clazz)` |

---

## 12. Security Model

### 12.1 The Two Permissions

**`android.permission.EXECUTE_APP_FUNCTIONS`**
- Required by caller apps to invoke AppFunctions belonging to other apps.
- On standard Android 16 devices: protection level `normal` — any app can declare it in its manifest and hold it.
- On some OEM/enterprise configurations: protection level `privileged` — only system-signed or pre-installed apps can hold it.
- An app can always execute its own AppFunctions without this permission.

**`android.permission.BIND_APP_FUNCTION_SERVICE`**
- This is a system permission, not declared by apps.
- It is used as `android:permission` on your `<service>` declaration.
- Declaring it on your service means that only the system process (which holds this permission) can bind to your service.
- If you omit this from your service declaration, any app on the device can bind to your AppFunctionService and invoke functions without going through the platform's permission checks.

**`android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED`** (platform internal)
- Used internally by privileged callers (e.g., Gemini on Pixel).
- Not available to third-party apps.

### 12.2 What the Platform Checks on Execution

When `AppFunctionManager.executeAppFunction()` is called:

1. The platform verifies the calling app holds `EXECUTE_APP_FUNCTIONS` (or `EXECUTE_APP_FUNCTIONS_TRUSTED` for privileged callers).
2. The platform verifies the target function's `isEnabled` state is `APP_FUNCTION_STATE_ENABLED` or `APP_FUNCTION_STATE_DEFAULT` (where the annotation's `isEnabled=true`).
3. The platform binds to the provider's `AppFunctionService` using `BIND_APP_FUNCTION_SERVICE` (meaning only the system can do this).
4. The platform passes the caller's `packageName` and `SigningInfo` to `onExecuteFunction()`. If your implementation needs to restrict which callers can invoke specific functions, inspect these values.

### 12.3 Caller Identity in Your Implementation

```kotlin
// In AppFunctionService.onExecuteFunction(), before the Jetpack wrapper calls
// executeFunction(), the platform has already verified the EXECUTE_APP_FUNCTIONS
// permission. But you can do additional checks in your implementation.

@AppFunction(isDescribedByKDoc = true)
suspend fun getPrivateData(
    appFunctionContext: AppFunctionContext,
): PrivateData? {
    // The AppFunctionContext does not directly expose the caller package name.
    // If you need caller identity, you must store it from onExecuteFunction()
    // in a thread-local or pass it through a context wrapper.
    // For most apps, trusting the platform's permission check is sufficient.
    return repository.getPrivateData()
}
```

---

## 13. Value Constraints

Value constraints let you restrict which values are valid for `Int` and `String` parameters. This communicates to AI agents that the parameter is an enum-like field with a fixed set of valid values.

### 13.1 @AppFunctionIntValueConstraint

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class AppFunctionIntValueConstraint(
    val enumValues: IntArray = [],
)
```

Usage on a parameter:

```kotlin
@AppFunction(isDescribedByKDoc = true)
suspend fun setMessagePriority(
    appFunctionContext: AppFunctionContext,
    messageId: String,
    /**
     * The priority level.
     * 0 = normal, 1 = high, 2 = urgent
     */
    @AppFunctionIntValueConstraint(enumValues = [0, 1, 2])
    priority: Int,
): DispatchMessage {
    return messageRepository.setPriority(messageId, priority)
}
```

### 13.2 @AppFunctionStringValueConstraint

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class AppFunctionStringValueConstraint(
    val enumValues: Array<String> = [],
)
```

Usage on a parameter:

```kotlin
@AppFunction(isDescribedByKDoc = true)
suspend fun filterConversations(
    appFunctionContext: AppFunctionContext,
    /**
     * The status filter.
     * "open" = active conversations, "closed" = archived, "all" = no filter.
     */
    @AppFunctionStringValueConstraint(enumValues = ["open", "closed", "all"])
    status: String,
): List<Conversation>? {
    return conversationRepository.getByStatus(status).ifEmpty { null }
}
```

### 13.3 On Data Class Properties

Constraints can also be applied to properties of `@AppFunctionSerializable` classes:

```kotlin
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CreateConversationRequest(
    /** The conversation type. */
    @AppFunctionStringValueConstraint(enumValues = ["direct", "group", "broadcast"])
    val type: String,
    /** The initial participant IDs. */
    val participantIds: List<String>,
)
```

### 13.4 Runtime Validation

As of alpha05, `AppFunctionData.Builder` validates constraint values when `build()` is called. If a value is outside the declared constraint, an `IllegalArgumentException` is thrown at build time (on the caller side), before the request is even sent.

---

## 14. URI Grants

If your function returns a URI pointing to a content provider resource (such as a file, image, or document), you need to grant the caller temporary read access to that URI. `AppFunctionUriGrant` provides this mechanism.

### 14.1 AppFunctionUriGrant Definition

```kotlin
@AppFunctionSerializable
data class AppFunctionUriGrant(
    val uri: android.net.Uri,
    val modeFlags: Int, // Intent.FLAG_GRANT_READ_URI_PERMISSION, etc.
)
```

`AppFunctionUriGrant` is itself annotated with `@AppFunctionSerializable`, so it can be used as a field in your return type data classes.

### 14.2 Usage

```kotlin
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Attachment(
    /** The attachment file name. */
    val fileName: String,
    /** The MIME type of the attachment. */
    val mimeType: String,
    /**
     * URI grant for the caller to access this attachment.
     * The platform automatically applies the grant when this function returns.
     */
    val content: AppFunctionUriGrant,
)

@AppFunction(isDescribedByKDoc = true)
suspend fun getMessageAttachment(
    appFunctionContext: AppFunctionContext,
    messageId: String,
    attachmentIndex: Int,
): Attachment? {
    val attachment = messageRepository.getAttachment(messageId, attachmentIndex)
        ?: return null
    return Attachment(
        fileName = attachment.name,
        mimeType = attachment.mimeType,
        content = AppFunctionUriGrant(
            uri = attachment.uri,
            modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    )
}
```

When the `AppFunctionService` returns a response containing an `AppFunctionUriGrant`, the platform automatically grants the calling app the specified URI permission. The caller can then open the URI with a `ContentResolver`.

### 14.3 Persistable URI Grants

As of alpha06, `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is supported in `AppFunctionUriGrant.modeFlags`. This lets the caller retain URI access across device reboots (by calling `ContentResolver.takePersistableUriPermission()`). Use this with caution — it gives the caller long-lived access.

```kotlin
val grant = AppFunctionUriGrant(
    uri = fileUri,
    modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
)
```

---

## 15. Deprecation Support

As of alpha07, you can mark an AppFunction as deprecated. Deprecated functions continue to appear in discovery results but carry a deprecation message that agents and caller apps can surface.

### 15.1 How to Deprecate

Use Kotlin's `@Deprecated` annotation combined with a `@AppFunctionDeprecationMetadata`-carrying KDoc pattern. In practice, the compiler reads the `@Deprecated` annotation on the function and generates `AppFunctionDeprecationMetadata` in the schema:

```kotlin
@Deprecated(
    message = "Use sendMessageV2() instead. This function will be removed in a future version.",
    replaceWith = ReplaceWith("sendMessageV2(appFunctionContext, conversationId, text, attachments)")
)
@AppFunction(isDescribedByKDoc = true)
suspend fun sendMessage(
    appFunctionContext: AppFunctionContext,
    conversationId: String,
    text: String,
): DispatchMessage {
    // legacy implementation
    return sendMessageV2(appFunctionContext, conversationId, text, emptyList())
}
```

### 15.2 Reading Deprecation on the Caller Side

```kotlin
functions.forEach { fn: AppFunctionMetadata ->
    if (fn.deprecation != null) {
        println("DEPRECATED: ${fn.id}")
        println("Reason: ${fn.deprecation!!.message}")
    }
}
```

`AppFunctionDeprecationMetadata`:

```kotlin
class AppFunctionDeprecationMetadata(
    val message: String, // The deprecation message
)
```

---

## 16. Complete Working Examples

### 16.1 Note-Taking App — Full Provider Implementation

This is the canonical example from the official documentation, with all wiring shown.

#### Data Classes

```kotlin
// NoteModels.kt
package com.example.notes.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/**
 * A note in the notes app.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Note(
    /** The note's unique identifier. */
    val id: Int,
    /** The note's title. */
    val title: String,
    /** The note's full content. */
    val content: String,
)
```

#### Function Class

```kotlin
// NoteFunctions.kt
package com.example.notes.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction

class NoteFunctions(
    private val noteRepository: NoteRepository,
) {
    /**
     * Lists all available notes.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listNotes(appFunctionContext: AppFunctionContext): List<Note>? {
        return noteRepository.getAllNotes().ifEmpty { null }
    }

    /**
     * Creates a new note.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     * @param title The title of the new note.
     * @param content The content body of the new note.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createNote(
        appFunctionContext: AppFunctionContext,
        title: String,
        content: String,
    ): Note {
        return noteRepository.createNote(title, content)
    }

    /**
     * Edits an existing note.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     * @param noteId The identifier of the note to edit.
     * @param title The new title, or null to leave unchanged.
     * @param content The new content, or null to leave unchanged.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun editNote(
        appFunctionContext: AppFunctionContext,
        noteId: Int,
        title: String?,
        content: String?,
    ): Note? {
        return noteRepository.updateNote(noteId, title, content)
    }

    /**
     * Deletes a note permanently.
     *
     * @param appFunctionContext The context in which the AppFunction is executed.
     * @param noteId The identifier of the note to delete.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun deleteNote(
        appFunctionContext: AppFunctionContext,
        noteId: Int,
    ) {
        noteRepository.deleteNote(noteId)
    }
}
```

#### Application Class

```kotlin
// NoteApplication.kt
package com.example.notes

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.example.notes.appfunctions.NoteFunctions
import com.example.notes.data.NoteRepositoryImpl

class NoteApplication : Application(), AppFunctionConfiguration.Provider {

    private val noteRepository by lazy { NoteRepositoryImpl(this) }

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(NoteFunctions::class.java) {
                NoteFunctions(noteRepository)
            }
            .build()
}
```

#### AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".NoteApplication"
        android:label="Notes App"
        android:icon="@mipmap/ic_launcher">

        <property
            android:name="android.app.appfunctions.app_metadata"
            android:resource="@xml/app_metadata" />

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Generated by KSP — verify the exact class name in build/generated/ksp/ -->
        <service
            android:name=".AppFunctionServiceImpl"
            android:exported="false"
            android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
            <intent-filter>
                <action android:name="android.app.appfunctions.AppFunctionService" />
            </intent-filter>
        </service>

    </application>

</manifest>
```

#### res/xml/app_metadata.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<AppFunctionAppMetadata
    xmlns:appfn="http://schemas.android.com/apk/res-auto"
    appfn:description="A notes application that lets you create, edit, and organize notes."
    appfn:displayDescription="Take notes and stay organized." />
```

---

### 16.2 Dispatch Messaging App — Full Provider Implementation

This example demonstrates AppFunctions in a messaging context, with attachments (URI grants), value constraints, and multiple function classes.

#### Data Classes

```kotlin
// DispatchModels.kt
package com.example.dispatch.appfunctions

import android.content.Intent
import android.net.Uri
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionUriGrant

/**
 * A conversation (thread) in Dispatch.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Conversation(
    /** The unique identifier of this conversation. */
    val id: String,
    /** The display name of this conversation. */
    val title: String,
    /**
     * The conversation type.
     */
    val type: String,
    /** The number of unread messages in this conversation. */
    val unreadCount: Int,
    /** The Unix timestamp (ms) of the most recent message. */
    val lastMessageAtMs: Long,
)

/**
 * A message sent or received in Dispatch.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DispatchMessage(
    /** The unique identifier of this message. */
    val id: String,
    /** The display name of the sender. */
    val senderName: String,
    /** The message body text. */
    val body: String,
    /** The Unix timestamp (ms) when this message was sent. */
    val sentAtMs: Long,
    /** Whether this message has been read. */
    val isRead: Boolean,
)

/**
 * An attachment associated with a message.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class MessageAttachment(
    /** The file name of the attachment. */
    val fileName: String,
    /** The MIME type of the attachment content. */
    val mimeType: String,
    /** URI grant providing read access to the attachment content. */
    val content: AppFunctionUriGrant,
)
```

#### Conversation Functions

```kotlin
// ConversationFunctions.kt
package com.example.dispatch.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionStringValueConstraint
import androidx.appfunctions.service.AppFunction
import com.example.dispatch.data.ConversationRepository

class ConversationFunctions(
    private val conversationRepository: ConversationRepository,
) {
    /**
     * Lists conversations matching the given status filter.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param status The conversation status filter. Use "open" for active conversations,
     *               "closed" for archived conversations, or "all" to retrieve everything.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listConversations(
        appFunctionContext: AppFunctionContext,
        @AppFunctionStringValueConstraint(enumValues = ["open", "closed", "all"])
        status: String = "open",
    ): List<Conversation>? {
        return conversationRepository.getByStatus(status).ifEmpty { null }
    }

    /**
     * Retrieves a single conversation by its identifier.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param conversationId The unique identifier of the conversation to retrieve.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getConversation(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
    ): Conversation? {
        return conversationRepository.getById(conversationId)
    }

    /**
     * Creates a new conversation.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param title The display title of the new conversation.
     * @param participantIds The IDs of users to include in the conversation.
     * @param type The conversation type.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createConversation(
        appFunctionContext: AppFunctionContext,
        title: String,
        participantIds: List<String>,
        @AppFunctionStringValueConstraint(enumValues = ["direct", "group", "broadcast"])
        type: String,
    ): Conversation {
        return conversationRepository.create(title, participantIds, type)
    }
}
```

#### Message Functions

```kotlin
// MessageFunctions.kt
package com.example.dispatch.appfunctions

import android.content.Intent
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionIntValueConstraint
import androidx.appfunctions.AppFunctionUriGrant
import androidx.appfunctions.service.AppFunction
import com.example.dispatch.data.MessageRepository

class MessageFunctions(
    private val messageRepository: MessageRepository,
) {
    /**
     * Sends a text message to a conversation.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param conversationId The unique identifier of the target conversation.
     * @param text The body text of the message to send.
     * @param priority The message priority level.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun sendMessage(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
        text: String,
        @AppFunctionIntValueConstraint(enumValues = [0, 1, 2])
        priority: Int = 0,
    ): DispatchMessage {
        return messageRepository.send(conversationId, text, priority)
    }

    /**
     * Retrieves recent messages from a conversation.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param conversationId The unique identifier of the conversation.
     * @param limit The maximum number of messages to return.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getMessages(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
        limit: Int?,
    ): List<DispatchMessage>? {
        val count = limit ?: 20
        return messageRepository.getRecent(conversationId, count).ifEmpty { null }
    }

    /**
     * Retrieves a file attachment from a specific message.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param messageId The unique identifier of the message containing the attachment.
     * @param attachmentIndex The zero-based index of the attachment.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getMessageAttachment(
        appFunctionContext: AppFunctionContext,
        messageId: String,
        attachmentIndex: Int,
    ): MessageAttachment? {
        val attachment = messageRepository.getAttachment(messageId, attachmentIndex)
            ?: return null
        return MessageAttachment(
            fileName = attachment.name,
            mimeType = attachment.mimeType,
            content = AppFunctionUriGrant(
                uri = attachment.uri,
                modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    /**
     * Marks all messages in a conversation as read.
     *
     * @param appFunctionContext The context in which this function runs.
     * @param conversationId The unique identifier of the conversation.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun markConversationRead(
        appFunctionContext: AppFunctionContext,
        conversationId: String,
    ) {
        messageRepository.markAllRead(conversationId)
    }
}
```

#### Application Class

```kotlin
// DispatchApplication.kt
package com.example.dispatch

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.example.dispatch.appfunctions.ConversationFunctions
import com.example.dispatch.appfunctions.MessageFunctions
import com.example.dispatch.data.ConversationRepositoryImpl
import com.example.dispatch.data.MessageRepositoryImpl

class DispatchApplication : Application(), AppFunctionConfiguration.Provider {

    private val conversationRepository by lazy { ConversationRepositoryImpl(this) }
    private val messageRepository by lazy { MessageRepositoryImpl(this) }

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(ConversationFunctions::class.java) {
                ConversationFunctions(conversationRepository)
            }
            .addEnclosingClassFactory(MessageFunctions::class.java) {
                MessageFunctions(messageRepository)
            }
            .build()
}
```

#### AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".DispatchApplication"
        android:label="Dispatch"
        android:icon="@mipmap/ic_launcher">

        <property
            android:name="android.app.appfunctions.app_metadata"
            android:resource="@xml/app_metadata" />

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".AppFunctionServiceImpl"
            android:exported="false"
            android:permission="android.permission.BIND_APP_FUNCTION_SERVICE">
            <intent-filter>
                <action android:name="android.app.appfunctions.AppFunctionService" />
            </intent-filter>
        </service>

    </application>

</manifest>
```

---

### 16.3 Caller App — Discovering and Invoking Dispatch Functions

```kotlin
// AgentViewModel.kt (in a separate caller/agent app)
package com.example.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val appFunctionManager: AppFunctionManager =
        AppFunctionManager.getInstance(application)
            ?: error("AppFunctions not supported on this device")

    private val _functions = MutableStateFlow<List<AppFunctionMetadata>>(emptyList())
    val functions: StateFlow<List<AppFunctionMetadata>> = _functions

    private val _result = MutableStateFlow<String>("")
    val result: StateFlow<String> = _result

    init {
        observeDispatchFunctions()
    }

    private fun observeDispatchFunctions() {
        val spec = AppFunctionSearchSpec(
            packageNames = setOf("com.example.dispatch")
        )
        appFunctionManager
            .observeAppFunctions(spec)
            .onEach { packages: List<AppFunctionPackageMetadata> ->
                val allFunctions = packages.flatMap { it.appFunctions }
                _functions.value = allFunctions
            }
            .launchIn(viewModelScope)
    }

    fun executeSendMessage(conversationId: String, text: String) {
        val fnMetadata = _functions.value
            .firstOrNull { it.id.contains("sendMessage") }
            ?: run { _result.value = "sendMessage function not found"; return }

        viewModelScope.launch {
            val params = AppFunctionData.Builder(
                fnMetadata.parameters,
                fnMetadata.components,
            )
                .setString("conversationId", conversationId)
                .setString("text", text)
                .setInt("priority", 1)
                .build()

            val request = ExecuteAppFunctionRequest(
                targetPackageName = "com.example.dispatch",
                functionIdentifier = fnMetadata.id,
                functionParameters = params,
            )

            val response = appFunctionManager.executeAppFunction(request)

            _result.value = when (response) {
                is ExecuteAppFunctionResponse.Success -> {
                    val id = response.returnValue.getString("id") ?: "unknown"
                    val body = response.returnValue.getString("body") ?: ""
                    "Message sent: id=$id body=$body"
                }
                is ExecuteAppFunctionResponse.Error -> {
                    "Error: ${response.error.errorMessage}"
                }
            }
        }
    }
}
```

---

## 17. Testing with AppFunctionTestRule

`AppFunctionTestRule` is in the `androidx.appfunctions:appfunctions-testing` artifact. It creates an in-process `AppFunctionManager` backed by Robolectric, so you can test your function implementations without a real Android 16 device.

### 17.1 Dependency

```kotlin
// In build.gradle.kts, test scope
testImplementation(libs.appfunctions.testing)
```

### 17.2 AppFunctionTestRule Definition

```kotlin
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // API 34+
class AppFunctionTestRule(context: android.content.Context) : TestRule {
    // The AppFunctionManager to use in your tests
    val appFunctionManager: AppFunctionManager

    override fun apply(base: Statement?, description: Description?): Statement
}
```

Note: `AppFunctionTestRule` requires API 34 (UpsideDownCake) minimum for Robolectric tests, even though the runtime feature requires API 36. This lets you run tests on CI without Android 16.

### 17.3 Complete Test Example

```kotlin
// DispatchFunctionsTest.kt
package com.example.dispatch.appfunctions

import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.testing.AppFunctionTestRule
import androidx.test.core.app.ApplicationProvider
import com.example.dispatch.data.FakeMessageRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class DispatchFunctionsTest {

    @get:Rule
    val appFunctionTestRule = AppFunctionTestRule(
        ApplicationProvider.getApplicationContext()
    )

    private val manager: AppFunctionManager
        get() = appFunctionTestRule.appFunctionManager

    @Test
    fun sendMessage_returnsMessageOnSuccess() = runTest {
        // Discover the sendMessage function
        val functions = manager
            .observeAppFunctions(AppFunctionSearchSpec())
            .firstOrNull()
            ?.flatMap { it.appFunctions }
            ?: emptyList()

        val sendMessageFn = functions.firstOrNull { it.id.contains("sendMessage") }
            ?: error("sendMessage function not registered")

        // Build the request
        val params = AppFunctionData.Builder(
            sendMessageFn.parameters,
            sendMessageFn.components,
        )
            .setString("conversationId", "conv-123")
            .setString("text", "Hello from test")
            .setInt("priority", 0)
            .build()

        val request = ExecuteAppFunctionRequest(
            targetPackageName = ApplicationProvider.getApplicationContext<android.app.Application>().packageName,
            functionIdentifier = sendMessageFn.id,
            functionParameters = params,
        )

        // Execute
        val response = manager.executeAppFunction(request)

        // Assert
        assertIs<ExecuteAppFunctionResponse.Success>(response)
        val messageId = response.returnValue.getString("id")
        assertEquals("conv-123", response.returnValue.getString("conversationId"))
    }

    @Test
    fun getMessages_returnsNullWhenEmpty() = runTest {
        // Similar structure — discover, build request, execute, assert
    }
}
```

---

## 18. The Full Exception Hierarchy

All AppFunctions exceptions extend `androidx.appfunctions.AppFunctionException`, which extends `java.lang.Exception`.

```
Exception
└── AppFunctionException
    │   - errorMessage: String? (human-readable message)
    │
    ├── AppFunctionAppException          (abstract — errors from the target app)
    │   ├── AppFunctionAppUnknownException       (unexpected error in the app)
    │   ├── AppFunctionNotSupportedException     (function not available/not supported)
    │   └── AppFunctionPermissionRequiredException (app-level permission missing)
    │
    ├── AppFunctionSystemException       (abstract — errors from the platform)
    │   ├── AppFunctionSystemUnknownException    (unexpected system error)
    │   └── AppFunctionCancelledException        (execution was cancelled)
    │
    ├── AppFunctionRequestException      (abstract — errors with the request itself)
    │   ├── AppFunctionDeniedException           (caller lacks EXECUTE_APP_FUNCTIONS permission)
    │   ├── AppFunctionDisabledException         (function is disabled)
    │   ├── AppFunctionFunctionNotFoundException (functionIdentifier not found in target app)
    │   ├── AppFunctionInvalidArgumentException  (required parameter missing or value violates constraint)
    │   ├── AppFunctionLimitExceededException    (rate limit or resource limit exceeded)
    │   ├── AppFunctionElementAlreadyExistsException (create operation failed — element exists)
    │   └── AppFunctionElementNotFoundException  (element referenced in request not found)
    │
    └── AppFunctionUnknownException      (unknown error code — check errorCode field)
        - errorCode: Int (the raw error code from the platform)
```

### 18.1 When Each Exception Is Thrown

| Exception | When thrown |
|---|---|
| `AppFunctionDeniedException` | Caller does not have `EXECUTE_APP_FUNCTIONS` permission, or the target app has explicitly denied access |
| `AppFunctionDisabledException` | The function's enabled state is `APP_FUNCTION_STATE_DISABLED` |
| `AppFunctionFunctionNotFoundException` | The `functionIdentifier` in the request does not match any function registered by the target app |
| `AppFunctionInvalidArgumentException` | A required parameter was omitted, or a value violates `@AppFunctionIntValueConstraint` / `@AppFunctionStringValueConstraint` |
| `AppFunctionCancelledException` | The `CancellationSignal` was triggered before the function completed |
| `AppFunctionElementNotFoundException` | The function's implementation threw this (e.g., noteId not found) |
| `AppFunctionElementAlreadyExistsException` | The function's implementation threw this (e.g., duplicate creation) |
| `AppFunctionLimitExceededException` | The function's implementation threw this (e.g., attachment size limit) |
| `AppFunctionPermissionRequiredException` | The function needs an additional permission from the user |
| `AppFunctionNotSupportedException` | The function exists but the app's current configuration does not support it |
| `AppFunctionAppUnknownException` | An unexpected exception occurred inside the function implementation |
| `AppFunctionSystemUnknownException` | An unexpected error occurred at the platform level |
| `AppFunctionUnknownException` | An unrecognized error code was returned from the platform |

### 18.2 Throwing Exceptions from Your Implementation

```kotlin
@AppFunction(isDescribedByKDoc = true)
suspend fun editNote(
    appFunctionContext: AppFunctionContext,
    noteId: Int,
    title: String?,
    content: String?,
): Note? {
    if (title == null && content == null) {
        throw AppFunctionInvalidArgumentException(
            "At least one of title or content must be provided"
        )
    }
    return noteRepository.updateNote(noteId, title, content)
        ?: throw AppFunctionElementNotFoundException("Note with id $noteId not found")
}
```

The Jetpack `AppFunctionService` wrapper catches all `AppFunctionException` subclasses and converts them to platform error responses. Unexpected non-`AppFunctionException` exceptions are converted to `AppFunctionAppUnknownException`.

---

## 19. Common Errors and Their Fixes

### Error: `AppFunctionServiceImpl` class not found at runtime

**Symptom:** The app crashes with `ClassNotFoundException` for the generated service class.

**Cause:** The KSP compiler did not generate the class, or you referenced the wrong name in the manifest.

**Fix:**
1. Make sure `ksp(libs.appfunctions.compiler)` is in your dependencies (not `implementation` or `annotationProcessor`).
2. Make sure `ksp { arg("appfunctions:aggregateAppFunctions", "true") }` is set.
3. After a successful build, look in `build/generated/ksp/debug/kotlin/` to find the actual generated class name.
4. Update `android:name` in your `<service>` declaration to match exactly.

---

### Error: `AppFunctionManager.getInstance()` returns null

**Symptom:** `getInstance()` returns `null` even on Android 16.

**Cause:** The device flag `enable_app_functions_schema_parser` is not enabled, or the library is being called before the platform has initialized AppFunctions.

**Fix:**
```
adb shell device_config put app_functions enable_app_functions_schema_parser true
```
Then restart the app. If this is a test device, ensure it is running Android 16 (API 36) or higher.

---

### Error: Functions not appearing in `observeAppFunctions()` results

**Symptom:** `observeAppFunctions()` emits an empty list even after installing the provider app.

**Causes and fixes:**
1. The provider app's `AndroidManifest.xml` does not reference the `app_functions.xml` resource. Check that the `<service>` declaration is present with the correct intent filter action.
2. The KSP compiler did not generate the XML schema file. Check `build/generated/ksp/` for the schema output. Make sure `aggregateAppFunctions=true` is set.
3. The AppSearch indexer has not run yet. On some devices there is a delay after installation. Try force-stopping and re-launching the provider app, or use `adb shell am broadcast` to trigger a package change broadcast.
4. The target package is not in the caller's `<queries>` block. Add it to your caller's manifest.

---

### Error: `AppFunctionDeniedException` when calling `executeAppFunction()`

**Symptom:** Every execution attempt throws `AppFunctionDeniedException`.

**Cause:** The caller app is missing the `EXECUTE_APP_FUNCTIONS` permission, or on this device it is a privileged permission and the caller is not a system app.

**Fix:** Add to the caller's manifest:
```xml
<uses-permission android:name="android.permission.EXECUTE_APP_FUNCTIONS" />
```
If the permission is privileged, the app must be pre-installed as a system app, which requires root access during development:
```
adb root
adb remount
adb push agent-debug.apk /system/priv-app/agent/agent.apk
adb reboot
```

---

### Error: Compile-time KSP error — "First parameter must be AppFunctionContext"

**Symptom:** Build fails with a KSP error about the first parameter type.

**Fix:** The first parameter of every `@AppFunction` function must be `androidx.appfunctions.AppFunctionContext`. Check that you are importing from the correct package.

---

### Error: Compile-time KSP error — "Class is not a data class"

**Symptom:** Build fails because `@AppFunctionSerializable` is applied to a non-data class.

**Fix:** Make the class a `data class`. If you need a sealed hierarchy, annotate each concrete `data class` individually.

---

### Error: Release build crashes but debug build works

**Symptom:** R8/ProGuard strips the generated classes or serialization code.

**Cause:** Known issue, fixed in alpha04. Make sure you are on `1.0.0-alpha04` or higher.

**Additional fix for older versions:** Add to your `proguard-rules.pro`:
```
-keep class androidx.appfunctions.** { *; }
-keep @androidx.appfunctions.AppFunctionSerializable class * { *; }
```

---

### Error: `AppFunctionInvalidArgumentException` — required field not set

**Symptom:** When the caller builds an `AppFunctionData` and calls `.build()`, it throws `IllegalArgumentException` about a required field.

**Cause:** As of alpha05, `AppFunctionData.Builder.build()` validates that all required (non-nullable) parameters are present.

**Fix:** Review which parameters are non-nullable in the function signature. All non-nullable parameters are required and must be set before calling `.build()`.

---

### Error: `List<ByteArray>` or `ByteArray` fields behaving incorrectly

**Symptom:** Functions with `ByteArray` or `List<ByteArray>` parameters produce unexpected results.

**Cause:** Known bug in alpha05 where `ByteArray`'s metadata was generated as `List<ByteArray>`. Fixed in alpha06.

**Fix:** Upgrade to `1.0.0-alpha06` or higher.

---

### Error: `PendingIntent` not working as return type

**Symptom:** Functions returning `PendingIntent` fail to compile or fail at runtime.

**Cause:** In alpha05 and earlier, `PendingIntent` was represented specially. In alpha06, it was migrated to be represented as a `Parcelable`. In alpha08, `get/set PendingIntent` APIs were removed and replaced with the generic `Parcelable` API.

**Fix:** On alpha08+, use `setParcelable(key, pendingIntent)` and `getParcelable<PendingIntent>(key)`.

---

## 20. Version History

All releases are in the `androidx.appfunctions` artifact group. All three artifacts (`appfunctions`, `appfunctions-service`, `appfunctions-compiler`) use the same version number.

---

### 1.0.0-alpha01 — May 7, 2025 (Initial Release)

**Introduced the three core artifacts:**
- `appfunctions` — Core client APIs for managing (enable/disable) and interacting with (search/execute) AppFunctions.
- `appfunctions-service` — Service-side APIs to easily expose app functionality as AppFunctions.
- `appfunctions-compiler` — Required KSP compiler to generate necessary code.

Built on top of `android.app.appfunctions` platform APIs.

---

### 1.0.0-alpha02 — June 4, 2025

**New features:**
- Support for Android 16 platform APIs.
- Better support for parameterized `@AppFunctionSerializable` types at compile time.

**API additions:**
- `AppFunctionSchemaDefinition` annotation — for agents to define predefined function schemas with `name`, `version`, and `category`.

**Bug fixes:**
- Error handling for missing runtime enabled state of AppFunctions.
- Fix in `observeAppFunctions()` API to observe changes in `AppFunctionComponentMetadata`.
- Additional error logging.

---

### 1.0.0-alpha03 — August 13, 2025

**New features:**
- `isDescribedByKDoc` on `@AppFunction` and `@AppFunctionSerializable` — use KDoc as function/field descriptions.
- `@AppFunctionIntValueConstraint` — restrict Int parameters to a set of valid values.
- `@AppFunctionStringValueConstraint` — restrict String parameters to a set of valid values.
- `AppFunctionUriGrant` — automatically grant URI permissions from function responses.
- `AppFunctionTestRule` — Robolectric testing support.

**API additions:**
- `AppFunctionStringValueConstraint`
- `AppFunctionIntValueConstraint`
- Refactored `AppFunctionPrimitiveTypeMetadata` into specific type classes.
- `description` field on `AppFunctionResponseMetadata`, `AppFunctionParameterMetadata`, `AppFunctionDataTypeMetadata`, `AppFunctionMetadata`.
- `resolveAppFunctionAppMetadata()` API on `AppFunctionPackageMetadata`.
- `isDescribedByKdoc` added to `@AppFunctionSerializable` and `@AppFunction`.
- `observeAppFunctions()` now returns `AppPackageMetadata`.
- Remove permission requirement from `setAppFunctionEnabled()` API.

**Bug fixes:**
- `AppFunctionManagerCompat` (now `AppFunctionManager`) only supports Android U+ devices.
- Property descriptions of shared serializable types now included in metadata XML.
- Description element added to generated metadata XML.

---

### 1.0.0-alpha04 — September 10, 2025

**Bug fixes:**
- Fix R8/ProGuard issues for release builds. Critical — if you are building release APKs, do not use alpha03 or earlier.

---

### 1.0.0-alpha05 — October 8, 2025

**New features:**
- Required fields are now enforced when constructing `AppFunctionData` via `Builder.build()`.
- `AppFunctionData` validates against constraint values at build time.

**API additions:**
- `AppFunctionService` Compat API additions (later renamed in alpha08).

**Bug fixes:**
- Required field check added to `AppFunctionData`.
- Fix: parameter optional state was not respected when overriding an interface.
- Fix: KSP now generates an empty XML file even when no `@AppFunction` annotations are present (AppSearch expects the file declared in the manifest to exist).

---

### 1.0.0-alpha06 — November 5, 2025

**New features:**
- Support for embedding resources (`AppFunctionTextResource`) as part of an AppFunction response.
- `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is now allowed in `AppFunctionUriGrant`.

**API additions:**
- `ResourceHolder` API.
- `AppFunctionTextResource` API.
- `AppFunctionOneOfTypeMetadata` API.
- Drop `Compat` suffix from Service APIs.
- `AppFunctionData` can be built from `AllOfType`.
- `getParcelable()` and `setParcelable()` APIs on `AppFunctionData`.

**Bug fixes:**
- Fix: ignoring a nullable required field would fail when constructing `AppFunctionData`.
- Fix: using `List<PendingIntent>` with `@AppFunction` would fail at compile time.
- Fix: `ByteArray`'s metadata was generated incorrectly as `List<ByteArray>`.

---

### 1.0.0-alpha07 — November 19, 2025

**New features:**
- Support for deprecating `@AppFunction` — annotate with `@Deprecated` and the schema includes a deprecation message.

**API additions:**
- `AppFunctionDeprecationMetadata` class with `message: String`.
- `deprecation: AppFunctionDeprecationMetadata?` field on `AppFunctionMetadata`.

**Bug fixes:**
- Fix: non-null required top-level parameters were not being validated correctly.

---

### 1.0.0-alpha08 — March 11, 2026

**New features:**
- Support for `Parcelable` types (beyond just `PendingIntent`).
- APIs to convert between Jetpack and platform request/response types.

**API changes:**
- `AppFunctionManagerCompat` renamed to `AppFunctionManager` (final name — no more `Compat` suffix).
- Removed get/set APIs for `PendingIntent` — replaced by generic `Parcelable` API.
- `setParcelable(key, value)` and `getParcelable<T>(key)` are the canonical way to pass `PendingIntent` and any other `Parcelable`.
- `toCompatExecuteAppFunctionRequest()` and `toPlatformExecuteAppFunctionRequest()` conversion methods.
- `toCompatExecuteAppFunctionResponse()` and `toPlatformExecuteAppFunctionResponse()` conversion methods.
- Parameter naming updated for styleguide conformance.

**Bug fixes:**
- Library now targets Java 11 (class file version 55). If your app uses Java 8 class file version, you may need Android's core library desugaring enabled.

---

## 21. Official Resources

- **AppFunctions Overview:** https://developer.android.com/ai/appfunctions
- **Jetpack Release Notes (all versions):** https://developer.android.com/jetpack/androidx/releases/appfunctions
- **Platform API Reference (`android.app.appfunctions`):** https://developer.android.com/reference/android/app/appfunctions/package-summary
- **Jetpack API Reference (`androidx.appfunctions`):** https://developer.android.com/reference/kotlin/androidx/appfunctions/
- **AppFunctionManager Javadoc:** https://developer.android.com/reference/kotlin/androidx/appfunctions/ExecuteAppFunctionRequest
- **AppFunctionService Platform Reference:** https://developer.android.com/reference/android/app/appfunctions/AppFunctionService
- **AppFunctionSystemException Reference:** https://developer.android.com/reference/kotlin/androidx/appfunctions/AppFunctionSystemException
- **Android Intelligent OS Blog Post (Feb 2026):** https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html
- **9to5Google AppFunctions + Gemini Article:** https://9to5google.com/2026/02/25/android-appfunctions-gemini/
- **FilipFan AppFunctionsPilot Sample (GitHub):** https://github.com/FilipFan/AppFunctionsPilot
- **AndroidX Source (appfunctions module):** https://github.com/androidx/androidx/tree/androidx-main/appfunctions
- **AppFunctions Core API Text:** https://raw.githubusercontent.com/androidx/androidx/androidx-main/appfunctions/appfunctions/api/current.txt
- **AppFunctions Service API Text:** https://raw.githubusercontent.com/androidx/androidx/androidx-main/appfunctions/appfunctions-service/api/current.txt
- **AppFunctions Testing API Text:** https://raw.githubusercontent.com/androidx/androidx/androidx-main/appfunctions/appfunctions-testing/api/current.txt
- **KSP Compiler Options Source:** https://raw.githubusercontent.com/androidx/androidx/androidx-main/appfunctions/appfunctions-compiler/src/main/java/androidx/appfunctions/compiler/AppFunctionCompilerOptions.kt
- **Model Context Protocol (for conceptual comparison):** https://modelcontextprotocol.io/

---

*This manual covers the AppFunctions API as of 1.0.0-alpha08 (March 2026). Because the library is still in experimental alpha, API-breaking changes should be expected in future releases. Always consult the official release notes before upgrading.*
