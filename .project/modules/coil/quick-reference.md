# Coil — Quick Reference

**What:** Kotlin-first image loading for Android + Compose. Backed by coroutines and OkHttp. Lightweight alternative to Glide.
**Context7 ID:** `/coil-kt/coil`
**Source:** https://coil-kt.github.io/coil/
**Version:** 3.0.4

## Gradle Dependencies

```kotlin
dependencies {
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")  // Compose support
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")  // OkHttp network layer
}
```

Note: Coil 3.x package is `io.coil-kt.coil3`. Coil 2.x was `io.coil-kt`.

## AsyncImage (Primary Composable)

```kotlin
// Simple
AsyncImage(
    model = "https://example.com/image.jpg",
    contentDescription = "Profile picture"
)

// With options
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data("https://example.com/image.jpg")
        .crossfade(true)
        .build(),
    placeholder = painterResource(R.drawable.placeholder),
    error = painterResource(R.drawable.error),
    contentDescription = "Avatar",
    contentScale = ContentScale.Crop,
    modifier = Modifier
        .size(100.dp)
        .clip(CircleShape)
)
```

## SubcomposeAsyncImage (State-Based)

```kotlin
// Different composables for each loading state
SubcomposeAsyncImage(
    model = "https://example.com/image.jpg",
    contentDescription = "Product",
    loading = { CircularProgressIndicator(modifier = Modifier.size(48.dp)) },
    error = { Icon(Icons.Default.Error, contentDescription = "Error") }
)
```

## Custom ImageLoader (Singleton)

```kotlin
// In Application class or Hilt module
class DispatchApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}

// Or in Compose
setSingletonImageLoaderFactory { context ->
    ImageLoader.Builder(context)
        .crossfade(true)
        .build()
}
```

## Painter State Observation

```kotlin
val painter = rememberAsyncImagePainter("https://example.com/image.jpg")
val state by painter.state.collectAsState()

when (state) {
    is AsyncImagePainter.State.Loading -> { /* show placeholder */ }
    is AsyncImagePainter.State.Success -> { /* show image */ }
    is AsyncImagePainter.State.Error -> { /* show error */ }
    is AsyncImagePainter.State.Empty -> { /* no request */ }
}

Image(painter = painter, contentDescription = "Image")
```

## Key Gotchas

- **Coil 3.x vs 2.x** — Package name changed from `io.coil-kt` to `io.coil-kt.coil3`. Import paths changed too
- **AsyncImage > SubcomposeAsyncImage** — AsyncImage performs better. Only use SubcomposeAsyncImage when you need different composables per state
- **Singleton ImageLoader** — Create ONE ImageLoader and share it. Per-request ImageLoaders waste memory and connections
- **OkHttp integration** — Coil 3 requires explicit network layer dependency (`coil-network-okhttp`). Without it, network loading fails silently
- **crossfade** — Off by default. Enable in ImageRequest or ImageLoader for smooth transitions
- **Memory cache** — Enabled by default. Keyed by URL + size + transformations. Same URL at different sizes = different cache entries
