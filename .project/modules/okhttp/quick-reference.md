# OkHttp — Quick Reference

**What:** HTTP client for Android/JVM. Connection pooling, GZIP, caching, HTTP/2. Foundation for Retrofit.
**Context7 ID:** `/square/okhttp`
**Source:** https://square.github.io/okhttp/
**Version:** 4.12.0 (stable) / 5.3.0 (alpha, Kotlin Multiplatform)

## Gradle Dependencies

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")      // Server-Sent Events
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0") // Debug logging
}
```

## Basic Client

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(AuthInterceptor(apiKey))
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .build()
```

## GET Request

```kotlin
val request = Request.Builder()
    .url("https://api.example.com/data")
    .header("Authorization", "Bearer $token")
    .build()

// Async
client.newCall(request).enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) { /* handle */ }
    override fun onResponse(call: Call, response: Response) {
        val body = response.body?.string()
    }
})

// Coroutine (suspend)
suspend fun fetchData(): String = withContext(Dispatchers.IO) {
    client.newCall(request).execute().use { response ->
        response.body?.string() ?: throw IOException("Empty body")
    }
}
```

## POST Request

```kotlin
val mediaType = "application/json; charset=utf-8".toMediaType()
val json = """{"message": "hello"}"""
val body = json.toRequestBody(mediaType)

val request = Request.Builder()
    .url("https://api.example.com/send")
    .post(body)
    .build()
```

## Server-Sent Events (SSE)

```kotlin
val request = Request.Builder()
    .url("https://api.example.com/stream")
    .header("Accept", "text/event-stream")
    .build()

val factory = EventSources.createFactory(client)
val listener = object : EventSourceListener() {
    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        // Called for each SSE event
        // Parse data (usually JSON)
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        // Connection lost — implement reconnection
    }

    override fun onClosed(eventSource: EventSource) {
        // Server closed the connection
    }
}

val eventSource = factory.newEventSource(request, listener)
// To cancel: eventSource.cancel()
```

## Interceptor Pattern

```kotlin
class AuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-API-Key", apiKey)
            .build()
        return chain.proceed(request)
    }
}
```

## Key Gotchas

- **SSE Accept header** — OkHttp 4.7+ auto-adds `Accept: text/event-stream` if not already set
- **Response body must be closed** — Use `.use {}` block or call `.close()`. Leaking connections kills performance
- **Call timeouts vs read timeouts** — `callTimeout` covers the entire call including redirects. `readTimeout` is per-read
- **Thread safety** — OkHttpClient is thread-safe and should be shared (singleton). Creating one per request wastes resources
- **Connection pooling** — Default pool keeps 5 idle connections for 5 minutes. Tune with `ConnectionPool(maxIdle, keepAlive, TimeUnit)`
- **SSE reconnection** — Not automatic. You must implement reconnection logic in `onFailure`
