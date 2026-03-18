# ExoPlayer Playlist Pre-buffering Behavior

Source: https://developer.android.com/media/media3/exoplayer/playlists
Source: Context7 /androidx/media — DefaultPreloadManager

## Default Behavior

ExoPlayer ALWAYS pre-buffers the next item in a playlist. This is by design
for smooth transitions between media items.

## DefaultPreloadManager (Media3 1.3.0+)

`DefaultPreloadManager` + `TargetPreloadStatusControl` gives control over
HOW MUCH to preload per item based on distance from current:

```kotlin
override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
    val distance = abs(index - currentPlayingIndex)
    return when {
        distance == 1 -> PreloadStatus.specifiedRangeLoaded(3000L)  // 3s
        distance == 2 -> PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
        distance in 3..4 -> PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
        else -> PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED  // no preload
    }
}
```

## Why This Doesn't Help Streaming DataSources

Pre-buffering opens the DataSource and reads bytes. For file/HTTP-GET sources,
this is fine — the data persists and can be re-read. For our streaming POST
DataSource, the HTTP response is consumed once. By the time ExoPlayer transitions
to the pre-buffered item, the response stream is closed and the bytes are gone.

## Correct Pattern for Streaming TTS

Play ONE item at a time. Hold pending messages in an application-level queue.
When STATE_ENDED fires, pop the next message and create a fresh DataSource.

This is NOT a workaround — it's the correct architecture for one-shot streaming
sources. ExoPlayer's playlist model assumes re-readable media.
