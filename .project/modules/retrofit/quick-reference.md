# Retrofit — Quick Reference

**What:** Type-safe HTTP client for Android/JVM. Turns HTTP APIs into Kotlin interfaces. Built on OkHttp.
**Context7 ID:** `/square/retrofit`
**Source:** https://square.github.io/retrofit/
**Version:** 2.11.0

## Gradle Dependencies

```kotlin
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    // OR for Gson:
    // implementation("com.squareup.retrofit2:converter-gson:2.11.0")
}
```

## API Interface

```kotlin
interface FileBridgeApi {

    @GET("api/health")
    suspend fun health(): HealthResponse

    @POST("api/chat/send")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse

    @GET("api/sessions")
    suspend fun getSessions(): List<SessionInfo>

    @GET("api/sessions/{id}/messages")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int = 50
    ): List<Message>

    @POST("api/tts/stream")
    suspend fun streamTts(@Body request: TtsRequest): ResponseBody

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") sessionId: String): Response<Unit>

    @Streaming
    @GET("api/chat/stream")
    suspend fun streamChat(@Query("session") sessionId: String): ResponseBody

    @Multipart
    @POST("api/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): UploadResponse
}
```

## Building Retrofit Instance

```kotlin
val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

val retrofit = Retrofit.Builder()
    .baseUrl("https://pop-os.tail12345.ts.net:8000/")
    .client(okHttpClient)  // shared OkHttpClient with interceptors
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()

val api = retrofit.create(FileBridgeApi::class.java)
```

## HTTP Annotations

| Annotation | Usage |
|-----------|-------|
| `@GET("path")` | GET request |
| `@POST("path")` | POST request |
| `@PUT("path")` | PUT request |
| `@DELETE("path")` | DELETE request |
| `@PATCH("path")` | PATCH request |
| `@Path("name")` | URL path parameter |
| `@Query("name")` | URL query parameter |
| `@Body` | Request body (serialized) |
| `@Header("name")` | Single header |
| `@Headers(...)` | Static headers |
| `@Streaming` | Stream response body (don't buffer in memory) |
| `@Multipart` | Multipart form data |
| `@FormUrlEncoded` | Form URL encoded body |

## Key Gotchas

- **suspend functions** — Retrofit 2.6+ supports suspend functions natively. Returns are auto-unwrapped
- **`Response<T>` vs `T`** — Return `T` for auto-throw on HTTP errors. Return `Response<T>` to handle error codes yourself
- **kotlinx.serialization converter** — Add LAST if mixing with other converters. It claims it can handle all types
- **`@Streaming`** — Required for large responses (SSE, file downloads). Without it, Retrofit buffers the entire response in memory
- **Base URL trailing slash** — MUST end with `/`. Relative paths are resolved against it. `"https://example.com/api"` is WRONG. `"https://example.com/api/"` is RIGHT
- **Thread safety** — Retrofit instances and API interfaces are thread-safe singletons
