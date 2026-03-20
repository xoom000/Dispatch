# kotlinx.serialization — Quick Reference

**What:** Kotlin-first JSON serialization. Compile-time, no reflection. Multiplatform.
**Context7 ID:** `/kotlin/kotlinx.serialization`
**Source:** https://github.com/Kotlin/kotlinx.serialization
**Version:** 1.6.3

## Gradle Dependencies

```kotlin
// build.gradle.kts (project)
plugins {
    kotlin("plugin.serialization") version "2.0.0" apply false
}

// build.gradle.kts (app)
plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

## Basic Usage

```kotlin
@Serializable
data class Message(
    val id: String,
    val content: String,
    val timestamp: Long,
    @SerialName("sender_name") val senderName: String  // JSON key differs from property name
)

// Encode
val json = Json.encodeToString(message)

// Decode
val message = Json.decodeFromString<Message>(jsonString)
```

## Json Configuration

```kotlin
val json = Json {
    ignoreUnknownKeys = true       // Don't crash on unknown JSON fields
    isLenient = true               // Allow unquoted strings, trailing commas
    prettyPrint = true             // Formatted output
    encodeDefaults = true          // Include properties with default values
    coerceInputValues = true       // Replace null with default for non-nullable fields
    explicitNulls = false          // Omit null fields from output
}
```

## Sealed Class / Polymorphism

```kotlin
@Serializable
sealed class StreamEvent {
    abstract val timestamp: Long
}

@Serializable
@SerialName("text")
data class TextEvent(
    override val timestamp: Long,
    val content: String
) : StreamEvent()

@Serializable
@SerialName("sentence")
data class SentenceEvent(
    override val timestamp: Long,
    val text: String,
    val index: Int
) : StreamEvent()

@Serializable
@SerialName("tool_use")
data class ToolUseEvent(
    override val timestamp: Long,
    val name: String,
    val input: JsonObject  // Raw JSON when structure varies
) : StreamEvent()

// Automatic type discriminator in JSON:
// {"type":"text","timestamp":123,"content":"hello"}
```

## Optional / Default Values

```kotlin
@Serializable
data class Config(
    val host: String,
    val port: Int = 8000,               // Default if missing in JSON
    val apiKey: String? = null,         // Nullable with default
    val tags: List<String> = emptyList() // Default empty list
)
```

## Per-Class Unknown Key Handling

```kotlin
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys  // Only this class ignores unknown keys
data class ExternalApiResponse(
    val id: String,
    val data: String
)
```

## Key Gotchas

- **Plugin required** — `kotlin("plugin.serialization")` must be applied. Without it, `@Serializable` is just an ignored annotation
- **No reflection** — Everything is compile-time. Classes must be annotated, can't serialize arbitrary objects
- **`@SerialName` for JSON keys** — Use when JSON key differs from Kotlin property name. Common with snake_case APIs
- **`ignoreUnknownKeys`** — OFF by default. Crashes if JSON has extra fields. Almost always want this ON for external APIs
- **Sealed class discriminator** — Default key is `"type"`. Change with `@JsonClassDiscriminator("kind")` on the sealed class
- **`JsonObject` / `JsonElement`** — Use for dynamic/unknown JSON structure. Parse later with `jsonObject["key"]?.jsonPrimitive?.content`
- **Retrofit integration** — Use `converter-kotlinx-serialization` artifact. Call `Json.asConverterFactory(mediaType)`
- **Enum serialization** — Use `@SerialName` on each enum value if JSON values differ from Kotlin names
