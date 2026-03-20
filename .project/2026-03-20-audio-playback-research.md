# Android Audio Playback — Open Source Reference Research
**Date:** 2026-03-20
**Purpose:** Reference document for comparing patterns against Dispatch's audio playback implementation. Covers Media3/ExoPlayer-based foreground services, server-streamed audio, TTS pipelines, earbud controls, and audio queue management.

---

## 1. Audio Playback Services (Media3/ExoPlayer Foreground Services)

These repos demonstrate the canonical pattern: `MediaSessionService` + `ExoPlayer` + notification + foreground service lifecycle.

---

### 1.1 androidx/media — Official Google Media3 Library + Demos
**URL:** https://github.com/androidx/media
**Stars:** ~2.7k | **Latest release:** 1.9.3 (March 16, 2026) | **Active (302 contributors)**

**What it is:** The canonical Jetpack Media3 library including ExoPlayer. The `/demos` directory is the single best reference for production-quality patterns.

**Key demo files:**
- `demos/session_service/src/main/java/androidx/media3/demo/session/DemoPlaybackService.kt` — full `MediaLibraryService` impl with `ExoPlayer`, Cast support, shuffle mode custom button preferences, foreground service listener, and `DataStore`-persisted notification artwork
- `demos/session/src/main/java/androidx/media3/demo/session/PlaybackService.kt` — simpler `MediaSessionService` with `PendingIntent` patterns for notification tap/back-stack navigation
- `libraries/session/src/main/java/androidx/media3/session/MediaSession.java` — canonical `onMediaButtonEvent` interception point

**Architecture pattern:**
- Service extends `MediaLibraryService` (or `MediaSessionService` for simpler cases)
- `ExoPlayer` created in `onCreate()` with audio focus, wrapped with `CastPlayer` for Chromecast support
- `MediaLibrarySession` built with custom `Callback` for domain logic
- `ServiceListener` inner class handles foreground service lifecycle and notification channel creation (API 26+)
- Notification artwork cached via `DataStore` with protobuf serialization
- `PendingIntent` with `FLAG_IMMUTABLE` for notification actions
- `TaskStackBuilder` for proper back-stack navigation

**Why look here:** This is the ground truth. Every architectural decision in other repos ultimately traces to these demos.

---

### 1.2 RcuDev/SimpleMediaPlayer — Clean Minimal Media3 Service
**URL:** https://github.com/RcuDev/SimpleMediaPlayer
**Stars:** 113 | **Language:** 100% Kotlin | **Last commit:** January 2023

**What it is:** A clean, modularized Media3 implementation designed to show only the minimum classes needed for background audio with notifications. Good teaching repo.

**Architecture pattern:**
- `SimpleMediaService` — extends `MediaSessionService`
- `SimpleMediaServiceHandler` — bridge between app and service using sealed classes for type-safe events/states (avoids raw callbacks)
- `SimpleMediaNotificationManager` — creates notification channel, manages foreground service start/stop
- `SimpleMediaNotificationAdapter` — auto-populates notification metadata from `MediaItem`
- `StateFlow` for reactive state (playback state, current item, position) across multiple screens
- Modular: `player-service/` module is separate from `app/` module

**Key files:**
- `player-service/src/.../SimpleMediaService.kt`
- `player-service/src/.../SimpleMediaServiceHandler.kt`
- `player-service/src/.../SimpleMediaNotificationManager.kt`

**Why look here:** The sealed class event bus between `ViewModel` and `MediaSessionService` is a clean pattern worth copying. Avoids the god-class problem.

---

### 1.3 myofficework000/Musify — Foreground Service + Koin + Compose
**URL:** https://github.com/myofficework000/Musify
**Stars:** 11 | **Language:** 100% Kotlin | **Last commit:** March 2025

**What it is:** Music player with Jetpack Compose + Media3 + Koin DI. Small but shows the full foreground service lifecycle with `PlayerNotificationManager`.

**Architecture pattern:**
- Foreground service with `PlayerNotificationManager` for interactive notification
- Koin for DI into the service
- `MediaStyle` notification with play/pause/skip actions
- Dynamic notification updates on track change

**Why look here:** Shows how to wire Koin DI into a `MediaSessionService` — relevant if Dispatch uses Hilt/Koin injected into the service.

---

### 1.4 oguzhaneksi/RadioRoam — Radio Streaming via MediaSessionService
**URL:** https://github.com/oguzhaneksi/RadioRoam
**Stars:** 11 | **Language:** 100% Kotlin

**What it is:** Demonstrates `MediaSessionService` for live radio streaming using Ktor + Media3 + Compose.

**Architecture pattern:**
- MVVM + `MediaSessionService`
- `MediaController` in ViewModel for UI-layer control
- `MediaBrowser` for station browsing/selection
- Ktor for network, Coroutines for async, Koin for DI, Coil for artwork

**Why look here:** Shows `MediaController` in the ViewModel pattern (rather than binding to the service directly) — the recommended modern approach.

---

### 1.5 FoedusProgramme/Gramophone — Production Music Player, Media3 Submodule
**URL:** https://github.com/FoedusProgramme/Gramophone
**Stars:** 2k | **Last release:** v1.0.17 (May 2025) | **114 contributors**

**What it is:** "A sane music player built with media3 and the Material Design library that strictly follows Android standards." Production-quality. Contains a custom `media3/` git submodule and a `hificore/` audio processing component.

**Architecture pattern:**
- Media3 as a git submodule (they may patch it)
- `hificore/` module with C++ for audio processing (ReplayGain 2.0, equalizer)
- Kotlin 80%, C++ 11%, Java 7%
- LRC/TTML/SRT synced lyrics

**Key files:**
- `app/src/...` — main service and UI
- `hificore/` — native audio processing
- `media3/` — submodule

**Why look here:** For understanding how to extend Media3 with native audio processing. Also good reference for production-grade `MediaSessionService` with complex custom commands.

---

## 2. Server-Streamed Audio (SSE, WebSocket, HTTP Streaming)

These patterns address receiving audio from a server and playing it progressively.

---

### 2.1 Media3 Progressive HTTP Streaming (Official Pattern)
**URL:** https://developer.android.com/media/media3/exoplayer/progressive
**URL:** https://github.com/androidx/media (customization docs)

**Pattern:** ExoPlayer handles progressive HTTP natively with `ProgressiveMediaSource`. For custom streaming:
- `DefaultDataSource.Factory` wraps `OkHttpDataSource.Factory` for OkHttp-backed networking
- `ResolvingDataSource` for just-in-time header injection (auth tokens, session IDs)
- Custom `DataSource` implementation for non-HTTP protocols (WebSocket, pipe-based)
- `CacheDataSource.Factory` wrapping `SimpleCache` for segment caching

```
ExoPlayer → ProgressiveMediaSource → ResolvingDataSource → OkHttpDataSource
```

**Key insight for Dispatch:** If audio comes back as a chunked HTTP response (e.g., WAV/PCM stream from a TTS server), ExoPlayer can handle it directly with a URI pointing to the HTTP endpoint, provided the server sends proper `Content-Type` and `Transfer-Encoding: chunked` headers.

---

### 2.2 beraldofilippo/playerlib — HTTP Stream Background Player
**URL:** https://github.com/beraldofilippo/playerlib
**Stars:** 22 | **Language:** 100% Kotlin | **Last commit:** 2018 (old but architectural reference)

**What it is:** Bare-bones library for background HTTP audio streaming with ExoPlayer, Android O+ compliant.

**Architecture pattern:**
- `PlayerService.kt` — background service managing ExoPlayer
- `PlayerActivity.kt` — binds to service via `ServiceConnection`
- `LaunchPlayerBroadcastReceiver.kt` — handles notification interactions
- `PlayerNotificationManager` for notification
- Audio focus management per Android O+ conventions

**Why look here:** Shows the minimal viable bound-service pattern for HTTP streaming. Outdated API but the architecture concept (bound service + `ServiceConnection` + `BroadcastReceiver` for notifications) is still a valid alternative to `MediaSessionService`.

---

### 2.3 biowink/oksse — Kotlin Multiplatform SSE Client with Coroutines
**URL:** https://github.com/biowink/oksse
**Stars:** 47 | **Language:** Kotlin

**What it is:** "Server Sent Events (SSE) client multiplatform library made with Kotlin and backed by coroutines." W3C spec compliant.

**Key files:**
- `common/` — shared multiplatform SSE protocol implementation
- `jvm-okhttp/` — JVM/Android implementation using OkHttp

**Why look here:** If Dispatch uses SSE to receive streaming TTS audio data or AI response text, this is the cleanest Kotlin-native SSE client. The coroutines-backed API maps naturally onto `Flow` for reactive consumption.

**Alternative:** `heremaps/oksse` (https://github.com/heremaps/oksse) — OkHttp extension for SSE, more stars but Java-first.

---

### 2.4 Streaming PCM via AudioTrack (Pattern, not a single repo)
**Reference:** Picovoice blog — Android Real-Time TTS Streaming (https://picovoice.ai/blog/android-streaming-text-to-speech/)

**Pattern for raw PCM streaming (not file-based):**
```
TTS Server → OkHttp chunked response → coroutine reads PCM bytes
  → Channel/Queue of PCM chunks
  → AudioTrack in MODE_STREAM writes chunks as they arrive
```

Key specifics:
- `AudioTrack(AudioFormat.ENCODING_PCM_16BIT, sampleRate, channelConfig, MODE_STREAM, bufferSize, sessionId)`
- Write chunks incrementally with `audioTrack.write(pcmChunk, 0, chunkSize)`
- Call `audioTrack.flush()` when stream ends
- This bypasses ExoPlayer entirely — appropriate when audio format is raw PCM, not a container format (MP3/WAV)

**When to use AudioTrack vs ExoPlayer for Dispatch:**
- Use `ExoPlayer` if the server sends a full audio file (WAV, MP3, Ogg) in a chunked HTTP response
- Use `AudioTrack` if the server sends raw PCM frames (e.g., Kokoro/Piper outputting 22050 Hz mono 16-bit PCM)

---

## 3. TTS Playback Pipelines (On-Device and Server TTS)

---

### 3.1 siva-sub/NekoSpeak — On-Device TTS with Kokoro, Piper, Pocket-TTS
**URL:** https://github.com/siva-sub/NekoSpeak
**Stars:** 45 | **Language:** Kotlin 2.0.0 | **Min SDK:** Android 35 | **License:** MIT

**What it is:** "Private, offline AI Text-to-Speech for Android" — system-wide TTS engine supporting Kokoro v1.0, Piper, KittenTTS, and Pocket-TTS. Integrates with any Android app using the system TTS API (MoonReader, @Voice Aloud, etc.).

**Key architecture — self-tuning parallel streaming:**
```
Text Input
  → Generator Coroutine (ONNX inference, produces audio frames)
  → Performance Tracker (measures generation-to-playback speed ratio)
  → Adaptive Buffer (8 frames fast device, 20-30 frames slow device)
  → Decoder Coroutine (consumes frames, plays audio)
```

- Uses CPU-only ONNX inference (deliberately rejects NNAPI/QNN — see ADR-001, ADR-002 in repo)
- Re-measures performance every 10 frames to handle thermal throttling
- Generator and decoder run in parallel via Kotlin coroutines + `Channel<AudioFrame>`

**Key files:**
- `app/src/main/java/com/nekospeak/tts/` — main TTS engine
- `app/src/main/cpp/` — JNI/C++ ONNX runtime bindings
- `app/src/main/assets/` — bundled Piper Amy Low model

**Why look here:** The adaptive buffer pattern is directly applicable to Dispatch's streaming TTS scenario where generation speed (server or on-device) varies and playback must not stutter. The `Channel<AudioFrame>`-based producer/consumer is a clean Kotlin-native approach.

---

### 3.2 puff-dayo/Kokoro-82M-Android — Minimal Kokoro On-Device TTS
**URL:** https://github.com/puff-dayo/Kokoro-82M-Android
**Stars:** 49 | **Language:** 100% Kotlin | **Last release:** February 2025 | **Status:** Archived

**What it is:** Minimal Android demo of Kokoro-82M TTS model (int8 quantized ONNX). Includes a voice style mixer.

**Key notes:**
- Archived July 2025
- Demonstrates how to initialize and run Kokoro ONNX model on Android
- Build: Android Studio Ladybug + Gradle

**Why look here:** Starting point for understanding Kokoro ONNX model integration on Android before connecting to a playback pipeline.

---

### 3.3 k2-fsa/sherpa-onnx — Production ONNX Speech Toolkit with Android TTS
**URL:** https://github.com/k2-fsa/sherpa-onnx
**Stars:** 10.9k | **Active** (master branch updated frequently)

**What it is:** "Speech-to-text, text-to-speech, speaker diarization, speech enhancement using next-gen Kaldi with ONNX runtime — without internet connection. Supports Android, iOS, HarmonyOS, Raspberry Pi, and 12 programming languages."

**Android/Kotlin specifics:**
- `android/` — main Android implementation directory
- `kotlin-api-examples/` — Kotlin API examples for TTS and ASR
- `java-api-examples/` — Java API examples
- Supports Piper models (prefixed `vits-piper-`)
- Supports Kokoro (via ONNX)
- Pre-built Android APKs at https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html

**Architecture for Android TTS:**
- JNI bridge to C++ ONNX runtime
- Kotlin/Java API wraps the native calls
- Audio output is PCM, which you then feed to `AudioTrack`

**Why look here:** This is the most production-mature on-device TTS/ASR toolkit available. If Dispatch ever needs on-device fallback TTS (e.g., Piper), this is the integration path. The Kotlin API examples show exactly how to call it.

---

### 3.4 just-ai/aimybox-android-assistant — Modular Voice Assistant Framework
**URL:** https://github.com/just-ai/aimybox-android-assistant
**Stars:** 274 | **Last commit:** January 2024 | **License:** Apache 2.0

**What it is:** "Embeddable custom voice assistant for Android" — pluggable architecture for STT, TTS, and NLU. Supports Google Platform TTS, multiple STT providers (Houndify, Google Cloud, Snowboy), and NLU (Dialogflow, Rasa, Aimylogic).

**Architecture:**
- `AimyboxAssistantFragment` — voice UI component (plug-and-play)
- Separate modules for each STT/TTS/NLU provider
- Conversation flow: STT → NLU → response → TTS playback

**Key files:**
- `app/src/main/java/com/justai/aimybox/assistant/AimyboxApplication.kt` — initialization pattern
- `app/src/main/java/com/justai/aimybox/assistant/MainActivity.kt` — activity integration

**Why look here:** The pluggable TTS provider pattern is useful if Dispatch wants to swap between on-device and server-side TTS. The modular architecture also shows how to isolate TTS from the rest of the app.

---

## 4. Earbud / Media Button Control

---

### 4.1 Media3 Built-in HEADSETHOOK Handling (Official Pattern)
**Reference:** https://github.com/androidx/media/issues/1493 (fixed in Media3)
**Docs:** https://developer.android.com/media/media3/session/control-playback

**How Media3 handles media buttons:**
- Media3 automatically handles media button events arriving at `MediaSession` and calls the appropriate `Player` method
- `KEYCODE_HEADSETHOOK` single tap → play/pause
- `KEYCODE_HEADSETHOOK` double tap → `seekToNext` (skip forward)
- Bug fixed (Issue #1493): the second tap of a double-click was not previously recognized as `HEADSETHOOK`, so skip-to-next didn't work on many earbuds — this is now fixed in Media3

**For custom earbud behavior** (e.g., double-tap to trigger voice reply in Dispatch):
```kotlin
// In MediaSession.Builder callback:
mediaSession = MediaSession.Builder(context, player)
    .setCallback(object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                && keyEvent.action == KeyEvent.ACTION_DOWN) {
                // Custom double-tap detection logic here
                return true // consume the event
            }
            return false // let Media3 handle it
        }
    })
    .build()
```

**Key issues to read:**
- https://github.com/androidx/media/issues/1493 — HEADSETHOOK double-click fix
- https://github.com/androidx/media/issues/249 — Custom Bluetooth headset command handling
- https://github.com/androidx/media/issues/1581 — MediaButtonReceiver and HEADSETHOOK edge cases

---

### 4.2 Notification Action Buttons (Official Media3 Pattern)
**Reference:** `DemoPlaybackService.kt` in androidx/media demos
**Issue:** https://github.com/androidx/media/issues/1163 — Custom notification controls discussion

**Pattern for custom notification buttons:**
- `MediaSession` publishes custom commands via `setCustomLayout()` with `CommandButton` list
- `MediaController` in the UI layer observes `customLayout` flow
- Double-tap toggle (e.g., shuffle on/off) shown in the `DemoPlaybackService` as a model

---

## 5. Audio Queue Management

---

### 5.1 ExoPlayer Built-in Playlist (Official Pattern)
**Docs:** https://developer.android.com/media/media3/exoplayer

**ExoPlayer's native queue:**
```kotlin
// Add items to queue
player.addMediaItem(mediaItem)           // append
player.addMediaItem(0, mediaItem)        // prepend
player.removeMediaItem(index)
player.moveMediaItem(fromIndex, toIndex)

// Observe queue state
player.addListener(object : Player.Listener {
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { }
    override fun onPlaybackStateChanged(playbackState: Int) { }
})
```

**Key for Dispatch's voice message queue:** ExoPlayer's playlist IS the queue. For sequential voice messages arriving from a server, the pattern is:
1. Each incoming audio segment → `MediaItem.fromUri(uri)` or custom `MediaItem` with `localConfiguration`
2. `player.addMediaItem(item)` as segments arrive
3. ExoPlayer automatically advances to the next item
4. `Player.Listener.onMediaItemTransition` fires on each advance

---

### 5.2 doublesymmetry/KotlinAudio — Queue Management Wrapper over ExoPlayer
**URL:** https://github.com/doublesymmetry/KotlinAudio
**Stars:** 64 | **Forks:** 90 | **Language:** 100% Kotlin

**What it is:** "An Android audio player written in Kotlin, making it simpler to work with audio playback from streams and files."

**Queue architecture:**
- `QueuedAudioPlayer` extends `AudioPlayer` — main class
- `currentItem`: currently playing
- `previousItems`: played items
- `nextItems`: queued items
- `next()`, `previous()`, `jumpToItem(index)` — navigation
- Automatic progression: when a track completes, next loads automatically
- `remove(index)`, `removeUpcomingItems()` — dynamic queue manipulation

**Why look here:** The `QueuedAudioPlayer` API is exactly the pattern Dispatch needs for queuing voice messages arriving sequentially. The previous/current/next split is cleaner than raw ExoPlayer index management.

**Key files:**
- `kotlin-audio/src/.../QueuedAudioPlayer.kt`
- `kotlin-audio-example/` — usage examples

---

### 5.3 AntennaPod/AntennaPod — Production Podcast Queue at Scale
**URL:** https://github.com/AntennaPod/AntennaPod
**Stars:** 7.7k | **Language:** 99.5% Java | **Last release:** v3.11.0 (January 2026)

**What it is:** The leading open-source Android podcast app. Highly sophisticated queue management (multiple queues, smart ordering, episode state tracking) with full Media3/ExoPlayer playback.

**Note:** Codebase is Java, not Kotlin. But architecture patterns transfer.

**Architecture:**
- `playback/` module — audio playback service
- `model/` — queue data structures and episode state
- `storage/` — queue persistence (database-backed)
- `MediaStyle` notification with all playback controls
- Bluetooth headset + earbud button handling via `MediaSession`
- Lock screen controls, Android Auto support

**Why look here:** For understanding how a production app handles:
- Queue state persistence across app restarts
- Episode download state vs. streaming state in the queue
- Multiple queue types (inbox, custom playlist)
- Sleep timer integration with the queue

---

### 5.4 XilinJia/Podcini — Kotlin Podcast Player with Circular Queues + Media3 AudioOffload
**URL:** https://github.com/XilinJia/Podcini
**Stars:** 234 | **Language:** Kotlin | **Last commit:** January 2025 | **Note:** Forked from AntennaPod Feb 2024, development halted

**What it is:** Full Kotlin rewrite of AntennaPod using Jetpack Compose + Media3 with `AudioOffloadMode` (battery optimized playback).

**Queue architecture:**
- Multiple circular queues (5 default, up to 10 customizable)
- Each feed can associate with a specific queue for automatic episode routing
- Queue "bin" — removed episodes kept in review
- Most recently updated queue activates on startup

**Why look here:** The circular queue model and per-feed queue association is a sophisticated pattern for managing ordered audio segments (analogous to Dispatch's per-contact or per-channel voice queues). Also the first example here explicitly using `AudioOffloadMode`.

---

## 6. Patterns Summary Table

| Pattern | Best Reference | Key File(s) |
|---|---|---|
| `MediaSessionService` + ExoPlayer baseline | `androidx/media` demos | `DemoPlaybackService.kt` |
| Sealed class event bus (Service ↔ ViewModel) | `RcuDev/SimpleMediaPlayer` | `SimpleMediaServiceHandler.kt` |
| `MediaController` in ViewModel (not bound service) | `oguzhaneksi/RadioRoam` | ViewModel files in `/app` |
| Custom notification buttons | `androidx/media` demos | `DemoPlaybackService.kt` (custom layout) |
| Earbud double-tap intercept | `androidx/media` issue #1493 | `MediaSessionImpl.java` in library |
| HTTP chunked stream → ExoPlayer | `beraldofilippo/playerlib` | `PlayerService.kt` |
| SSE client (Kotlin coroutines) | `biowink/oksse` | `common/` + `jvm-okhttp/` |
| PCM streaming via AudioTrack | Picovoice article + NekoSpeak | `NekoSpeak` TTS engine |
| Adaptive buffer (gen speed varies) | `siva-sub/NekoSpeak` | `app/src/main/java/com/nekospeak/tts/` |
| On-device Kokoro/Piper TTS | `k2-fsa/sherpa-onnx` | `android/` + `kotlin-api-examples/` |
| Queue abstraction over ExoPlayer | `doublesymmetry/KotlinAudio` | `QueuedAudioPlayer.kt` |
| Production queue persistence | `AntennaPod/AntennaPod` | `playback/` + `storage/` modules |
| Multi-queue / circular queue | `XilinJia/Podcini` | `app/src/` |
| Modular TTS provider swap | `just-ai/aimybox-android-assistant` | `AimyboxApplication.kt` |

---

## 7. Recommended Reading Order for Dispatch

If you're auditing Dispatch's audio playback service against best practices, read in this order:

1. **`DemoPlaybackService.kt`** in `androidx/media` — understand the complete canonical service implementation
2. **`RcuDev/SimpleMediaPlayer`** — see the sealed class event bus pattern applied cleanly
3. **`doublesymmetry/KotlinAudio`** / **ExoPlayer playlist docs** — confirm Dispatch's queue model
4. **`siva-sub/NekoSpeak`** — study the adaptive buffer pattern for streaming TTS
5. **Media3 issue #1493** — understand earbud double-tap behavior and what to override
6. **`biowink/oksse`** — if Dispatch uses SSE for receiving server audio or AI text

---

## 8. Notes on Dispatch-Specific Concerns

**TTS streaming from server (e.g., Kokoro on a backend):**
- If server sends WAV/MP3: use `ProgressiveMediaSource` with `OkHttpDataSource.Factory` — ExoPlayer handles the rest
- If server sends raw PCM: use `AudioTrack` in `MODE_STREAM`, write chunks from a coroutine reading the response body
- If server sends PCM via SSE events (base64 encoded): use `biowink/oksse` to receive events, decode, write to `AudioTrack`

**Sequential voice message queue (messages arriving one after another):**
- ExoPlayer's native playlist handles this: `player.addMediaItem()` as each segment arrives
- For more control over previous/current/next state, wrap with `doublesymmetry/KotlinAudio`'s `QueuedAudioPlayer` pattern
- Persist queue to a `Room` database (per `AntennaPod` pattern) if messages must survive process death

**Earbud controls:**
- Media3 handles single-tap play/pause and double-tap skip automatically
- For Dispatch-specific double-tap behavior (e.g., trigger voice reply), override `onMediaButtonEvent` in `MediaSession.Callback` and intercept `KEYCODE_HEADSETHOOK` with custom double-tap timing logic (~500ms window)

---

*Sources: GitHub search, repo READMEs, official Android docs, androidx/media issue tracker. All URLs verified as of 2026-03-20.*
