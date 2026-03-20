# Audio Reference Repos — Study Index for Dispatch

All repos cloned to subdirectories of this folder (shallow `--depth 1`).
Media3 demos fetched individually via curl (repo is multi-GB).

---

## 1. NekoSpeak
**Repo:** `NekoSpeak/`
**Source:** https://github.com/siva-sub/NekoSpeak
**Pattern it teaches:** ONNX TTS engine pipeline with Mutex-serialized synthesis,
adaptive token-batch streaming, and a clean engine-swap pattern.

### Key Files

| Relative Path | What it Demonstrates | Maps to Dispatch |
|---|---|---|
| `app/src/main/java/com/nekospeak/tts/engine/TtsEngine.kt` | Suspend-based engine interface: `initialize()`, `generate(text, speed, voice, callback)`, `stop()`, `release()` — all the hooks Dispatch's TTS pipeline needs | `DispatchTtsEngine.kt` (create this interface) |
| `app/src/main/java/com/nekospeak/tts/NekoTtsService.kt` | Mutex-guarded synthesis with engine hot-swap; `synthMutex.withLock` prevents use-after-free; `stopRequested` volatile flag; `floatToPcm16()` streaming chunk writer | `DispatchTtsService.kt` — adopt the mutex + stopFlag + chunk-write loop verbatim |
| `app/src/main/java/com/nekospeak/tts/engine/KokoroEngine.kt` | Full ONNX inference loop: G2P phonemization → token batching → `processBatch()` → float callback; immediate-first-sentence flush heuristic; adaptive `MAX_TOKENS` per model; per-model audio trimming; 32-bit ARM mmap workaround | `DispatchKokoroEngine.kt` — adopt the batching and trimming logic directly |
| `app/src/main/java/com/nekospeak/tts/engine/Phonemizer.kt` | Wraps espeak-ng via JNI for G2P; provides `phonemize()` and `tokenize()` | `DispatchPhonemizer.kt` |
| `app/src/main/java/com/nekospeak/tts/engine/EngineFactory.kt` | Factory that reads `currentModel` pref and creates the right engine implementation | `DispatchEngineFactory.kt` — use this pattern to switch between Kokoro / Piper / future engines |

### Critical pattern: adaptive buffer streaming
In `KokoroEngine.generate()`:
- First sentence bypasses the batch accumulator and flushes immediately → first audio within ~200ms
- Subsequent sentences accumulate tokens up to `currentMaxTokens` (150 for Kokoro, 400 for Kitten)
- If a single sentence exceeds the limit, it is processed whole (never split mid-sentence to avoid audio artifacts)
- `stopFlag` is checked between batches and inside `processBatch` — Dispatch must replicate this

---

## 2. KotlinAudio
**Repo:** `KotlinAudio/`
**Source:** https://github.com/doublesymmetry/KotlinAudio
**Pattern it teaches:** Layered audio player architecture (Base → Queued),
`MutableSharedFlow`-based event bus, `BufferConfig`/`DefaultLoadControl` wiring,
audio focus management, `ForwardingPlayer` for intercepting media-button actions.

### Key Files

| Relative Path | What it Demonstrates | Maps to Dispatch |
|---|---|---|
| `kotlin-audio/src/main/java/com/doublesymmetry/kotlinaudio/players/BaseAudioPlayer.kt` | Complete ExoPlayer setup: `BufferConfig` → `DefaultLoadControl`, audio attributes, `ForwardingPlayer` intercept for external actions, audio focus request/abandon with ducking, `PlayerListener.onEvents()` multi-event coalescing | `DispatchBasePlayer.kt` — copy the `setupBuffer()` and `onAudioFocusChange()` implementations |
| `kotlin-audio/src/main/java/com/doublesymmetry/kotlinaudio/players/QueuedAudioPlayer.kt` | Clean queue API over ExoPlayer: `add()`, `remove(indexes: List<Int>)` (descending sort!), `move()`, `jumpToItem()`, `removeUpcomingItems()` / `removePreviousItems()` | `DispatchQueueManager.kt` — Dispatch's article-queue operations should match this API exactly |
| `kotlin-audio/src/main/java/com/doublesymmetry/kotlinaudio/event/PlayerEventHolder.kt` | All player events as `MutableSharedFlow<T>(replay=1)` — stateChange, playbackEnd, audioItemTransition, positionChanged, onAudioFocusChanged, onPlayerActionTriggeredExternally | `DispatchPlayerEventBus.kt` — use SharedFlow(replay=1) for every state, not LiveData |
| `kotlin-audio/src/main/java/com/doublesymmetry/kotlinaudio/models/AudioPlayerState.kt` | Enum: LOADING, READY, BUFFERING, PAUSED, STOPPED, PLAYING, IDLE, ENDED, ERROR — canonical 9-state machine | `DispatchPlaybackState.kt` — Dispatch's sealed class should cover all 9 states |
| `kotlin-audio/src/main/java/com/doublesymmetry/kotlinaudio/models/BufferConfig.kt` | Data class wrapping `minBuffer`, `maxBuffer`, `playBuffer`, `backBuffer` in ms — all nullable with ExoPlayer defaults | `DispatchPlayerConfig.kt` — expose identical buffer tunables |

### Critical pattern: ForwardingPlayer intercept
`BaseAudioPlayer.createForwardingPlayer()` wraps ExoPlayer with overrides for
`play()`, `pause()`, `seekToNext()`, etc. that emit `MediaSessionCallback` events
before delegating. Dispatch must do the same to know when Bluetooth headset buttons
or Android Auto triggered a command vs. the app itself.

---

## 3. Google Media3 Demos (session_service)
**Repo:** `media3-demos/`
**Source:** https://github.com/androidx/media (demos/session_service only — fetched via curl)
**Pattern it teaches:** Canonical `MediaLibraryService` structure, `onPlaybackResumption`,
trusted vs. untrusted controller permission split, DataStore persistence of last-played position,
`CommandButton` for shuffle toggle in notification.

### Key Files

| Relative Path | What it Demonstrates | Maps to Dispatch |
|---|---|---|
| `demos/session_service/src/main/java/androidx/media3/demo/session/DemoPlaybackService.kt` | `MediaLibraryService` lifecycle: `initializeSessionAndPlayer()`, `buildPlayer()` (CastPlayer wrapping ExoPlayer), `storeCurrentMediaItem()` to DataStore proto, `retrieveLastStoredMediaItem()`, `onForegroundServiceStartNotAllowedException()` fallback notification | `DispatchPlaybackService.kt` — this is the template; replace CastPlayer with plain ExoPlayer |
| `demos/session_service/src/main/java/androidx/media3/demo/session/DemoMediaLibrarySessionCallback.kt` | `onConnect()` with trusted/untrusted permission split; `onAddMediaItems()` / `onSetMediaItems()` / `onPlaybackResumption()` — the three callbacks Dispatch must implement for Auto/Wear compat | `DispatchSessionCallback.kt` |
| `demos/session_service/src/main/java/androidx/media3/demo/session/MediaItemTree.kt` | Browse tree: ROOT → ALBUM/ARTIST/GENRE folders → leaf items; `expandItem()` merges stub metadata with real URI; `search()` with multi-word tokenized matching | `DispatchMediaItemTree.kt` — Dispatch's browse tree (Feeds → Episodes) should follow this object structure |
| `demos/session_service/src/main/java/androidx/media3/demo/session/MainActivity.kt` | `MediaBrowser.Builder` with `SessionToken(context, ComponentName(...))` and lifecycle-aware connect/disconnect | `DispatchPlayerActivity.kt` — Dispatch's UI must use MediaBrowser, not direct ExoPlayer access |

### Critical pattern: playback resumption
`DemoPlaybackService.onPlaybackResumption()` is called when the system tries to restart
playback (e.g., BT headset connect with app closed). It reads DataStore for the last
media ID + position, expands it, and returns `MediaItemsWithStartPosition`. Dispatch
needs this to resume podcast position after device reboot.

### Critical pattern: trusted controller permission split
In `DemoMediaLibrarySessionCallback.onConnect()`, trusted controllers (same app) get
full `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` + `DEFAULT_PLAYER_COMMANDS`.
Untrusted (e.g., Android Auto) get only `COMMAND_GET_CURRENT_MEDIA_ITEM`,
`COMMAND_GET_TRACKS`, `COMMAND_GET_METADATA`. Dispatch should mirror this.

---

## 4. SimpleMediaPlayer
**Repo:** `SimpleMediaPlayer/`
**Source:** https://github.com/RcuDev/SimpleMediaPlayer
**Pattern it teaches:** Minimal, idiomatic Media3 + Hilt wiring — sealed-class event bus
from service handler to ViewModel, `PlayerNotificationManager` integration,
clean Hilt module that shows exactly how to wire ExoPlayer → MediaSession → Service.

### Key Files

| Relative Path | What it Demonstrates | Maps to Dispatch |
|---|---|---|
| `player-service/src/main/java/com/rcudev/player_service/service/SimpleMediaServiceHandler.kt` | Sealed classes `PlayerEvent` (PlayPause, Backward, Forward, Stop, UpdateProgress) and `SimpleMediaState` (Initial, Ready, Progress, Buffering, Playing) emitted via `MutableStateFlow`; 500ms progress polling loop; single `onPlayerEvent()` suspend fun for UI to call | `DispatchPlayerHandler.kt` — adopt the sealed-class command pattern for UI→service comms |
| `player-service/src/main/java/com/rcudev/player_service/service/SimpleMediaService.kt` | Minimal `MediaSessionService`: injects `MediaSession` and `SimpleMediaNotificationManager` via Hilt; delegates `onGetSession()` cleanly; graceful stop in `onDestroy()` | `DispatchService.kt` — keep the service this thin; push all logic into handler |
| `player-service/src/main/java/com/rcudev/player_service/service/notification/SimpleMediaNotificationManager.kt` | `PlayerNotificationManager.Builder` with `SimpleMediaNotificationAdapter`; `setMediaSessionToken()`, compact view config (fwd + rewind in compact, no next), `startForeground()` | `DispatchNotificationManager.kt` |
| `player-service/src/main/java/com/rcudev/player_service/di/SimpleMediaModule.kt` | Hilt `@Module`: `AudioAttributes` → `ExoPlayer` → `MediaSession` → `SimpleMediaServiceHandler` dependency chain; `setHandleAudioBecomingNoisy(true)` at ExoPlayer construction | `DispatchServiceModule.kt` (Hilt) — replicate this exact DI chain |

### Critical pattern: sealed-class event bus
`PlayerEvent` is the command type (UI → handler); `SimpleMediaState` is the state
type (handler → UI). Both flow through the same `SimpleMediaServiceHandler`.
Dispatch should separate these: one sealed class per direction, one `StateFlow`
per state dimension (not one giant `when`).

---

## 5. Gramophone
**Repo:** `Gramophone/`
**Source:** https://github.com/FoedusProgramme/Gramophone
**Pattern it teaches:** Production-hardened `MediaLibraryService` with dedicated
playback `HandlerThread`, `CoroutineScope` scoping by concern (default/IO/limitedParallelism),
`ReplayGainAudioProcessor`, `LastPlayedManager`, custom `SessionCommand` broadcasting,
`MediaBrowser` in a `LifecycleOwner`-aware `AndroidViewModel`.

### Key Files

| Relative Path | What it Demonstrates | Maps to Dispatch |
|---|---|---|
| `app/src/main/java/org/akanework/gramophone/logic/GramophonePlaybackService.kt` | `HandlerThread("ExoPlayer:Playback", THREAD_PRIORITY_AUDIO)` for the playback Looper; scoped coroutines (`scope`, `lyricsFetcher`, `bitrateFetcher`) with `limitedParallelism(1)`; `SESSION_SET_TIMER` / `SERVICE_GET_LYRICS` custom commands broadcast via `mediaSession.broadcastCustomCommand()`; `BroadcastReceiver` for seek intents from widget | `DispatchPlaybackService.kt` — adopt the HandlerThread for playback, scoped coroutines per concern |
| `app/src/main/java/org/akanework/gramophone/ui/MediaControllerViewModel.kt` | `AndroidViewModel` holding `MediaBrowser.Builder(...).buildAsync()` future; lifecycle-safe connect in `onStart()` / disconnect in `onStop()`; error handling for `SecurityException` (session rejected); `customCommandListeners` and `connectionListeners` as `LifecycleCallbackList` | `DispatchPlayerViewModel.kt` — this is the correct way to own MediaBrowser in a ViewModel |
| `app/src/main/java/org/akanework/gramophone/logic/utils/LastPlayedManager.kt` | Persists last-played mediaId + position; read on `onPlaybackResumption` | `DispatchLastPlayedManager.kt` |
| `app/src/main/java/org/akanework/gramophone/logic/utils/ReplayGainAudioProcessor.kt` | Custom `AudioProcessor` for gain normalization — shows how to inject audio processing into the ExoPlayer render chain | Dispatch TTS volume normalization can follow this pattern |
| `app/src/main/java/org/akanework/gramophone/logic/utils/exoplayer/EndedWorkaroundPlayer.kt` | Wraps ExoPlayer to fix `STATE_ENDED` → `STATE_IDLE` race on `seekTo(0)` after playlist end — a known Media3 bug | Copy verbatim into Dispatch; this bug will appear |

### Critical pattern: HandlerThread for playback
`GramophonePlaybackService` creates `HandlerThread("ExoPlayer:Playback", THREAD_PRIORITY_AUDIO)`
and passes its `Looper` to `ExoPlayer.Builder().setPlaybackLooper(...)`. This prevents
audio glitches when the main thread is busy. Dispatch must do the same.

### Critical pattern: scoped coroutines by concern
Three separate scopes in the service:
- `scope = CoroutineScope(Dispatchers.Default)` — general work
- `lyricsFetcher = CoroutineScope(Dispatchers.IO.limitedParallelism(1))` — serialized lyric fetches
- `bitrateFetcher = CoroutineScope(Dispatchers.IO.limitedParallelism(1))` — serialized bitrate reads

Dispatch's equivalent: `ttsPipeline = CoroutineScope(Dispatchers.IO.limitedParallelism(1))`
for serialized TTS synthesis, `articleFetcher = CoroutineScope(Dispatchers.IO)` for feed fetching.

---

## Cross-Repo Summary: Which Dispatch Files Should Adopt Which Pattern

| Dispatch File (to create/update) | Primary Reference | Secondary Reference |
|---|---|---|
| `DispatchTtsEngine.kt` (interface) | NekoSpeak `TtsEngine.kt` | — |
| `DispatchKokoroEngine.kt` | NekoSpeak `KokoroEngine.kt` | — |
| `DispatchTtsService.kt` | NekoSpeak `NekoTtsService.kt` (Mutex + stop flag) | — |
| `DispatchPlaybackService.kt` | Media3 `DemoPlaybackService.kt` (lifecycle) | Gramophone (HandlerThread, scoped coroutines) |
| `DispatchSessionCallback.kt` | Media3 `DemoMediaLibrarySessionCallback.kt` | — |
| `DispatchQueueManager.kt` | KotlinAudio `QueuedAudioPlayer.kt` | — |
| `DispatchPlayerEventBus.kt` | KotlinAudio `PlayerEventHolder.kt` | SimpleMediaPlayer `SimpleMediaServiceHandler.kt` |
| `DispatchPlaybackState.kt` | KotlinAudio `AudioPlayerState.kt` | SimpleMediaPlayer `SimpleMediaState` sealed class |
| `DispatchNotificationManager.kt` | SimpleMediaPlayer `SimpleMediaNotificationManager.kt` | — |
| `DispatchServiceModule.kt` (Hilt) | SimpleMediaPlayer `SimpleMediaModule.kt` | — |
| `DispatchPlayerViewModel.kt` | Gramophone `MediaControllerViewModel.kt` | — |
| `DispatchPlayerConfig.kt` | KotlinAudio `BufferConfig.kt` + `BaseAudioPlayer.setupBuffer()` | — |
| `DispatchMediaItemTree.kt` | Media3 `MediaItemTree.kt` | — |
| `DispatchLastPlayedManager.kt` | Gramophone `LastPlayedManager.kt` | Media3 `DemoPlaybackService.storeCurrentMediaItem()` |

---

## Quick-Reference: Audio Architecture Decision Tree for Dispatch

```
Dispatch audio stack (top to bottom):

UI Layer
  └── DispatchPlayerViewModel (AndroidViewModel)
        └── MediaBrowser.buildAsync() [Gramophone pattern]

IPC / Session Layer
  └── DispatchPlaybackService : MediaLibraryService  [Media3 DemoPlaybackService template]
        ├── MediaLibrarySession + DispatchSessionCallback [Media3 DemoMediaLibrarySessionCallback]
        ├── HandlerThread(THREAD_PRIORITY_AUDIO)          [Gramophone]
        └── scoped coroutines per concern                  [Gramophone]

Playback Engine Layer
  └── ExoPlayer (via EndedWorkaroundPlayer)              [Gramophone EndedWorkaroundPlayer]
        ├── ForwardingPlayer for media-button intercept  [KotlinAudio BaseAudioPlayer]
        ├── BufferConfig → DefaultLoadControl            [KotlinAudio BaseAudioPlayer.setupBuffer()]
        └── Audio focus request/abandon/duck             [KotlinAudio BaseAudioPlayer.onAudioFocusChange()]

Queue Management
  └── DispatchQueueManager                               [KotlinAudio QueuedAudioPlayer]

Event Bus
  └── DispatchPlayerEventBus (MutableSharedFlow)        [KotlinAudio PlayerEventHolder]
        └── DispatchPlaybackState (9-state enum)         [KotlinAudio AudioPlayerState]

TTS Pipeline (parallel to ExoPlayer for synthesized content)
  └── DispatchTtsEngine (interface)                      [NekoSpeak TtsEngine]
        └── DispatchKokoroEngine (ONNX)                  [NekoSpeak KokoroEngine]
              ├── Mutex-serialized synthesis             [NekoSpeak NekoTtsService]
              ├── Adaptive token batching                [NekoSpeak KokoroEngine.generate()]
              └── Float→PCM16 streaming chunks           [NekoSpeak NekoTtsService.floatToPcm16()]

Notifications
  └── DispatchNotificationManager                        [SimpleMediaPlayer SimpleMediaNotificationManager]
        └── PlayerNotificationManager.Builder            [SimpleMediaPlayer pattern]

DI Wiring
  └── DispatchServiceModule (Hilt)                       [SimpleMediaPlayer SimpleMediaModule]
        AudioAttributes → ExoPlayer → MediaSession → Handler
```
