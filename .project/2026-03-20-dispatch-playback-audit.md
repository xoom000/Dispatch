# Dispatch Android — Audio/Playback Architecture Audit

**Date:** 2026-03-20
**Scope:** Full audio pipeline — FCM ingestion, TTS, streaming, ExoPlayer, earbud controls, Media3 session, foreground service, UI
**Files Audited:** 14 source files across playback, audio, tts, network, fcm, data, ui packages

---

## 1. System Overview

Dispatch has two distinct audio pipelines that operate in parallel and serve different purposes. Understanding the boundary between them is critical to understanding both the architecture and its current debt.

### Pipeline A: FCM Voice Messages (Primary — Production Path)
Incoming dispatch voice messages arrive as FCM high-priority data pushes. The FCM service transforms the payload into a `DispatchPlaybackService` intent and fires `startForegroundService`. The service POSTs the text to the Kokoro TTS streaming endpoint on Oasis (GPU server), receives raw WAV bytes over HTTP chunked transfer, strips the 44-byte WAV header, and feeds raw PCM to ExoPlayer via a custom `DataSource`. ExoPlayer drives a `MediaSession` which handles audio focus, Bluetooth routing, and earbud button events. This path is streaming — first audio arrives in approximately 500ms with no full-file wait.

### Pipeline B: On-Demand Replay and Streaming Chat TTS (Legacy Path)
UI replay buttons and streaming chat responses (when a sentence-level `StreamEvent.Sentence` arrives during an active `sendStreaming` call) take a different route. `AudioStreamClient` downloads a complete WAV over HTTP using `HttpURLConnection`, strips the header manually, and plays raw PCM bytes through a directly-instantiated `AudioTrack`. No ExoPlayer. No `MediaSession`. No audio focus. No earbud controls. This path is blocking and callers must manage their own threading.

There is also a third, mostly-dormant path: on-device Piper TTS via `TtsEngine` and Sherpa-ONNX. This is initialized at app startup, kept alive as a fallback, and used only when the GPU server fails or for status messages when GPU download also fails. For streaming chat TTS (`StreamingTtsQueue`), sentences are routed through `DispatchPlaybackService` (Pipeline A) via `startForegroundService` intents — meaning they do use Media3, but they bypass the `AudioStreamClient` entirely.

---

## 2. File-by-File Analysis

### `playback/DispatchPlaybackService.kt`

**What it does:**
The central playback engine. Extends `MediaSessionService` (Media3), creates and owns an `ExoPlayer` instance and a `MediaSession`. Receives voice messages as `startForegroundService` intents, manages an internal software message queue for serializing streaming requests, and fires status messages back through TTS.

**Key classes:**
- `DispatchPlaybackService` — the service itself; 905 lines, owns ExoPlayer, MediaSession, HTTP clients, stream registry, message queue, voice recognition, and voice reply
- `TtsStreamDataSource` — inner class implementing `BaseDataSource`; POSTs to Kokoro's `/api/tts/stream` and feeds the chunked response directly to ExoPlayer. Implements the full `transferInitializing → transferStarted → bytesTransferred → transferEnded` contract correctly.
- `DispatchForwardingPlayer` — inner class extending `ForwardingPlayer`; intercepts `seekToNext()` (double tap) to trigger voice reply, passes all other commands through to ExoPlayer
- `PlayerEventListener` — inner class implementing `Player.Listener`; handles `STATE_ENDED` to dequeue next message, `onIsPlayingChanged` to update `PlaybackStateHolder`, and `onPlayerError` for queue recovery
- `PendingMessage` — data class holding queued message parameters while the player is busy

**Connections:**
- Receives intents from `DispatchFcmService` (voice messages) and `StreamingTtsQueue` (streaming chat sentences)
- Reads TTS endpoint from `TailscaleConfig.TTS_STREAM_SERVER`
- Writes playback state to `PlaybackStateHolder` (singleton, shared with UI)
- Calls `VoiceReplyCoordinator.sendVoiceReply` after successful speech recognition
- Reads last-received-message cursor from `VoiceNotificationRepository.getAtCursor()` to route voice replies
- Uses `RawPcmExtractor` via `ProgressiveMediaSource.Factory` to decode the stripped PCM stream

**Patterns observed:**
- The WAV header stripping rationale is well-documented: Kokoro's streaming endpoint sends a placeholder `data_size=0x7FFFFF00` in the WAV header that would cause `WavExtractor` to compute an infinite `bytesLeft` and never reach `STATE_ENDED`. Stripping the header and using a custom `RawPcmExtractor` is the correct fix.
- The `ConcurrentHashMap` stream registry (keyed by UUID) is the right approach for passing parameters from the enqueue site to `TtsStreamDataSource.open()`, since ExoPlayer may call `open()` on a different thread.
- Retry logic for status messages falls back from GPU to Piper. The call to `ttsEngine.speakBlocking()` happens inside `serviceScope.launch(Dispatchers.IO)`, which is correct (blocking operation on IO thread).
- The `VOICE_CUE_DELAY_MS = 2500L` hard-coded delay between playing the reply cue and starting speech recognition is a timing guess, not a state machine. It does not wait for the cue to actually finish playing.

**Issues flagged:**
1. **God class.** At 905 lines, `DispatchPlaybackService` does too much. It contains: HTTP download logic, streaming DataSource implementation, ExoPlayer queue management, earbud gesture dispatch, speech recognition lifecycle, voice reply HTTP send, status TTS, notification management, and temp file cleanup. Any one of these is extraction-worthy.
2. **Two OkHttpClient instances.** `httpClient` (30s read timeout, for WAV download) and `streamingClient` (60s read timeout, for streaming). The WAV download path (`downloadWav`) is only used for status messages now — the primary FCM path is streaming-only. The `downloadWav` method and `httpClient` are legacy infrastructure kept alive only to serve `playStatusMessage`.
3. **`downloadWav` uses `Thread.sleep`.** The retry delay in `downloadWav` calls `Thread.sleep(RETRY_DELAY_MS)` with no coroutine awareness. This runs on whatever thread calls it — in `playStatusMessage`, it runs on `Dispatchers.IO`, which is acceptable but fragile. A future caller on the wrong thread would be a bug.
4. **Speech recognition inside the MediaSessionService.** `SpeechRecognizer` is created, used, and destroyed inside `launchSpeechRecognition`. This is functional but mixes concerns. The voice reply flow (play cue → wait → recognize → send → play confirmation) is a full interaction loop that the service owns entirely. This should be in `VoiceReplyCoordinator` or a dedicated `VoiceReplySession` class.
5. **`pendingCount` is decremented in two places.** Both `onMediaItemTransition` and `onPlaybackStateChanged(STATE_ENDED)` decrement `pendingCount`. For streaming items (the primary path), `STATE_ENDED` fires — that's one decrement. But if ExoPlayer's playlist had multiple items (legacy WAV path), `onMediaItemTransition` would fire too. These two listeners can double-decrement if both paths are active simultaneously. Currently safe because the streaming path never loads multiple ExoPlayer items, but fragile.
6. **`tapHandler` is created but never used in the final code.** The tap debounce `Handler` and `pendingSingleTap` fields are declared but `pendingSingleTap` is never assigned. Dead code from an earlier tap-debounce implementation that was abandoned.
7. **Auto-resume logic on stale pause.** When a new message arrives while the player is in `STATE_READY` but not playing (user paused), the service auto-resumes. This overrides user intent. A user who paused to take a call or focus will have audio restarted without consent.
8. **`exported="true"` on the service.** The `AndroidManifest.xml` declares `DispatchPlaybackService` with `android:exported="true"`. This is required for Media3 `MediaController` to bind from other processes (Bluetooth, Android Auto), but it means any app on the device could send intents to start audio playback. There is no signature-level permission protecting it.

---

### `playback/StreamingTtsQueue.kt`

**What it does:**
A thin singleton bridge from `MessagesViewModel` to `DispatchPlaybackService`. Each time the streaming chat SSE receives a `StreamEvent.Sentence`, the ViewModel calls `StreamingTtsQueue.enqueue(text, department)`. The queue maps the department name to a Kokoro voice identifier and fires a `startForegroundService` intent.

**Key classes:**
- `StreamingTtsQueue` — singleton with `enqueue(text, department)` and `reset()`. No internal queue; the queue lives in `DispatchPlaybackService.messageQueue`.

**Connections:**
- Called by `MessagesViewModel.handleStreamEvent` on `StreamEvent.Sentence` events
- Fires intents to `DispatchPlaybackService`
- `reset()` called at the start of `sendStreaming` to allow future cancel/drain logic

**Patterns observed:**
- The class is appropriately thin. It is a translation layer, not a queue. Its name is slightly misleading — it queues nothing, it fires intents.
- Voice selection by department name is hardcoded in a `when` block with 7 entries. This mirrors the CLI-side mapping in the server's `dispatch config.py`.

**Issues flagged:**
1. **Voice mapping is duplicated.** `DispatchFcmService` has its own `voiceForSender` method with 13 entries. `StreamingTtsQueue.voiceForDepartment` has 7 entries. The mappings partially overlap but differ (e.g., FCM maps "boardroom" to "am_eric", while `StreamingTtsQueue` maps "boardroom" to "am_puck"). There is no single source of truth for sender→voice mapping.
2. **`reset()` does nothing.** The method is a documented hook for future cancellation but currently has no effect. The comment is accurate, but callers may assume it has an effect.
3. **`startForegroundService` can throw `ForegroundServiceStartNotAllowedException` on Android 12+** if the app is in the background during streaming. The `try/catch` in `enqueue` catches this and logs it, but there is no retry or user feedback.

---

### `playback/RawPcmExtractor.kt`

**What it does:**
A custom Media3 `Extractor` that reads raw 16-bit PCM at 24kHz mono from the `TtsStreamDataSource`. The `WavExtractor` cannot be used because the Kokoro streaming endpoint sends a corrupted WAV header with an invalid data size. This extractor skips container parsing entirely and treats the input as a raw byte stream of known format.

**Key classes:**
- `RawPcmExtractor` — implements `Extractor`. Parameterized with sample rate, channel count, and PCM encoding. Produces samples at approximately 10 per second (100ms chunks), matching `WavExtractor`'s internal pacing.

**Connections:**
- Used by `DispatchPlaybackService.playStreamingItem` via `ProgressiveMediaSource.Factory`
- `factory(sampleRate)` static method returns an `ExtractorsFactory` that wraps a single instance

**Patterns observed:**
- The pacing logic (targeting 10 samples/second) prevents ExoPlayer from getting enormous single-sample writes that could cause buffer stall or stuttering.
- `sniff()` always returns `true` — acceptable because this extractor is registered as the only option for `tts://` URIs.
- `seek()` resets frame counters — correct for a live stream that cannot actually seek.
- The partial-flush on `RESULT_END_OF_INPUT` is important: without it, the last sub-100ms chunk would be lost at stream end.

**Issues flagged:**
1. **Sample size calculation in `read()` has a subtle redundancy.** Inside the `while` loop, `bytesToRead` is computed as `min(bytesLeft, (targetSampleSizeBytes - pendingOutputBytes).toLong())`. After the first iteration, `pendingOutputBytes` has increased so the inner expression can differ from `bytesLeft`. The logic is correct but would benefit from a clearer variable name or a comment explaining the loop invariant.
2. **Hard-coded to 24kHz, mono, 16-bit as defaults.** These match Kokoro's output, but if the server configuration ever changes, there is no detection — playback would produce garbled audio silently. A format negotiation mechanism (even just logging the sample rate from the WAV header before stripping it) would be safer.

---

### `playback/VoiceReplyCoordinator.kt`

**What it does:**
A singleton that encapsulates the cmail send operation triggered by earbud double-tap. When the user completes voice recognition, `DispatchPlaybackService` calls `sendVoiceReply`, which calls `CmailRepository.sendCmail` and writes the result to `CmailOutboxRepository`.

**Key classes:**
- `VoiceReplyCoordinator` — one method, `sendVoiceReply(department, message, threadId): String?`. Returns the session ID on success, null on failure.

**Connections:**
- Called by `DispatchPlaybackService.sendVoiceReply`
- Calls `CmailRepository.sendCmail`
- Writes to `CmailOutboxRepository`

**Patterns observed:**
- This is the correct abstraction. The service does not need to know about `CmailRepository` or `CmailOutboxRepository`. The coordinator is a clean boundary.
- The subject line "Voice reply from Nigel" is hardcoded. This is a persona name that should be configurable.

**Issues flagged:**
1. **The coordinator does not update `PlaybackStateHolder`** for recording start/end. `PlaybackStateHolder` has `onVoiceReplyStarted` and `onVoiceReplyEnded` methods, but they are never called — neither from the coordinator nor from the service. The MiniPlayerBar has a recording state (`isRecording`, `replyTarget`) that is wired up in the UI but never actually set. The recording indicator in the player bar will never appear.
2. **`invoke = true` is always passed to `sendCmail`.** This tells the cmail system to immediately invoke the Claude agent for this department. This is probably correct for a voice reply, but it is not configurable.

---

### `fcm/DispatchFcmService.kt`

**What it does:**
Extends `FirebaseMessagingService`. Receives FCM high-priority data messages. Routes them by type: `type="action"` goes to `DispatchAccessibilityService` for phone automation; all other messages become `VoiceNotification` records stored in `VoiceNotificationRepository` and trigger a `DispatchPlaybackService` foreground service start.

**Key classes:**
- `DispatchFcmService` — 247 lines. Handles token refresh, message routing, action dispatch, and showing a visible notification alongside the audio.

**Connections:**
- Calls `startForegroundService` with a `DispatchPlaybackService.createIntent` intent
- Calls `VoiceNotificationRepository.add` to store the notification for cursor navigation
- Calls `DispatchAccessibilityService.executeAction` for action-type payloads
- Shows a `NotificationCompat` notification via `NotificationManager`
- Uses `EntryPointAccessors` to get Hilt-injected dependencies (correct pattern for `FirebaseMessagingService`)

**Patterns observed:**
- The spoken text format is `"$sender says: $messageText"` — this is duplicated in `AudioStreamClient.replayMessage`. The formatting should be centralized.
- Voice resolution falls back from FCM payload `voice` field to `voiceForSender(sender)` if absent. This is the right priority order.
- The `traceId` is passed all the way to `DispatchPlaybackService` for end-to-end latency logging.
- File attachment metadata (fileUrl, fileUrls, etc.) is parsed and stored in `VoiceNotification` but not used by the audio pipeline. It goes to the UI layer only.

**Issues flagged:**
1. **Voice mapping duplicated from `StreamingTtsQueue`.** `voiceForSender` in `DispatchFcmService` and `voiceForDepartment` in `StreamingTtsQueue` both perform sender→voice lookups with different entries and inconsistent values. A `VoiceMapper` singleton injected into both would fix this.
2. **Notification uses `System.currentTimeMillis().toInt()` as notification ID.** On a fast device receiving multiple messages in rapid succession, two notifications could get the same ID (integer overflow from millis is not an issue, but the truncation to Int loses the high bits). Using an `AtomicInteger` counter would be safer.
3. **`flushLogs()` is called twice** — once after action routing returns, once at the end of the normal message path. The log flush is for SSH-visible debugging and is not harmful, but it's inconsistent.
4. **`TODO` comment for the notification icon.** The notification uses `android.R.drawable.ic_dialog_info` (a system icon) as a placeholder. A custom icon is missing.

---

### `network/SseConnectionService.kt`

**What it does:**
A foreground `Service` that maintains a persistent SSE connection to File Bridge at `/events/stream`. Receives real-time events for chat bubbles, message chunks, cmail updates, whiteboard refreshes, and tool events. Does not directly handle audio — dispatch voice messages arrive via FCM, not SSE (the SSE `dispatch_message` event is explicitly handled as informational-only).

**Key classes:**
- `SseConnectionService` — the service
- `SseListener` — inner class extending OkHttp's `EventSourceListener`

**Connections:**
- Writes to `ChatBubbleRepository`, `MessageRepository` (live chunks), and `CmailEventBus`
- Publishes connection state, event feed refresh, and whiteboard refresh as static `StateFlow` objects on the companion object
- Started by `BootReceiver` on device boot or package replacement

**Patterns observed:**
- The static `StateFlow` on the companion object is a service-to-UI signal bus pattern. It avoids the need for `bindService` while still allowing reactive observation. This is intentional and documented.
- Exponential backoff reconnect: 2s, 4s, 8s, 16s, max 30s. Standard.
- `Last-Event-ID` header is sent on reconnect for resumable SSE.
- WakeLock is acquired only during reconnection delays (not during steady-state connection), which is correct.
- The `dataSync` foreground service type in the manifest is correct for SSE. `DispatchPlaybackService` uses `mediaPlayback`, also correct.

**Issues flagged:**
1. **`dispatch_message` SSE event is silently ignored.** The comment says "Full voice payload arrives via FCM." This is correct, but if FCM is delayed or dropped (network condition), there is no SSE fallback. The SSE event carries the thread ID but not the message text or voice — it cannot trigger audio on its own. This is an architectural gap rather than a bug.
2. **Static `StateFlow` on companion object** is a shared mutable singleton that makes testing harder. A repository-style wrapper injected via Hilt would be cleaner.

---

### `audio/PlaybackStateHolder.kt`

**What it does:**
A Hilt singleton holding a `StateFlow<PlaybackUiState>`. The service writes to it; the `MiniPlayerBar` reads from it. Acts as the cross-process (service → UI) state bridge without requiring `bindService`.

**Key classes:**
- `PlaybackStateHolder` — event-sourced state machine with `onPlaybackStarted`, `onPlaybackPaused`, `onPlaybackResumed`, `onQueueEmpty`, `onQueueCountChanged`, `onVoiceReplyStarted`, `onVoiceReplyEnded`
- `PlaybackUiState` — immutable data class

**Connections:**
- Written by `DispatchPlaybackService`
- Read by `MiniPlayerBar` composable

**Issues flagged:**
1. **`onVoiceReplyStarted` and `onVoiceReplyEnded` are never called** (see `VoiceReplyCoordinator` findings above). Dead API surface.
2. **No persistence.** When the service is destroyed and recreated, `PlaybackUiState` resets to the default (inactive). This means the MiniPlayerBar disappears between messages even if messages are queued quickly. Acceptable for the current use case.

---

### `tts/TtsEngine.kt`

**What it does:**
Manages on-device Piper TTS via Sherpa-ONNX (`OfflineTts`). Extracts the ONNX model from assets on first run (via `ModelManager`), initializes an `AudioTrack` for playback, and exposes `speak()`, `speakBlocking()`, and `playAudio()`. Falls back to Android's system `TextToSpeech` if Piper fails.

**Key classes:**
- `TtsEngine` — singleton; owns `offlineTts: Any?` (typed as `Any` to avoid eager native library loading), `audioTrack`, `fallbackTts`, and `executor` (single-thread)
- The `initPiperWhenReady()` method polls `ModelManager.state` in a 500ms loop on the executor thread until the model is ready.

**Connections:**
- Called by `DispatchPlaybackService.playStatusMessage` as a last-resort fallback when GPU TTS fails
- `ModelManager` dependency for model file paths and readiness state
- Not called by the primary FCM message path

**Issues flagged:**
1. **Busy-polling in `initPiperWhenReady`.** A 500ms sleep loop waiting for model readiness on the executor thread blocks the single-thread executor from doing anything else during model extraction (which can take several seconds for the 75MB ONNX file). Using `StateFlow.filter { it is ModelState.Ready }.first()` in a coroutine would be non-blocking.
2. **`offlineTts` typed as `Any?`** to avoid Sherpa-ONNX class loading at service startup. This is a pragmatic workaround but erases type safety. Every usage requires an unchecked cast. A lazy-initialized wrapper class or `interface` would be cleaner.
3. **The singleton `audioTrack` is shared between `speakPiper` (executor-based) and `playAudio` (also executor-based).** Both methods use the same executor so there is no concurrency issue. However, `playAudio` creates a new `AudioTrack` if the sample rate does not match, creating a temporary track that is released after use. The original singleton `audioTrack` is not touched. This is correct but easy to get wrong.
4. **`TtsEngine` is initialized and alive for the entire app lifetime** even though it is used only as a fallback. The Piper model initialization (75MB ONNX + espeak data) happens at startup regardless of whether GPU TTS is available. If GPU TTS is always reachable, this is wasted work.

---

### `tts/ModelManager.kt`

**What it does:**
Manages extraction of the bundled Piper model from APK assets to `filesDir/piper/`. Tracks extraction state via a `StateFlow<ModelState>`. Once the files exist on disk, re-runs return `ModelState.Ready` immediately without re-extracting.

**Issues flagged:**
1. **No version check on the extracted model.** If the bundled model changes between app versions, the stale on-disk version will be used until the user clears app data. A version file written alongside the model (and checked against `BuildConfig.VERSION_CODE`) would handle this.

---

### `network/AudioStreamClient.kt`

**What it does:**
Synchronous WAV download + AudioTrack playback for on-demand replay and diagnostic use. Downloads a complete WAV from `TailscaleConfig.TTS_SERVER`, strips the 44-byte header, and plays raw PCM via `AudioTrack`. Blocks the calling thread throughout.

**Connections:**
- Called by `MessagesViewModel` via the `_audioQueue` Channel consumer for UI replay buttons
- Called from `DispatchPlaybackService` indirectly via `TtsEngine.speakBlocking` (which does not use `AudioStreamClient`) — actually no, the service uses `downloadWav` directly for status messages, not `AudioStreamClient`

**Issues flagged:**
1. **Two separate download implementations.** `AudioStreamClient.downloadWav` (using `HttpURLConnection`) and `DispatchPlaybackService.downloadWav` (using OkHttp) both do the same thing — download a WAV from `TailscaleConfig.TTS_SERVER`, check the header size, return bytes. They even share the same constants (44-byte header, 2-retry logic, 2000ms retry delay, 5s connect timeout, 30s read timeout). This is textbook duplication. A shared `KokoroTtsDownloader` class would eliminate it.
2. **`waitForDrain` is a polling loop.** The drain check polls `playbackHeadPosition` every 50ms for up to 3 seconds. ExoPlayer handles drain automatically; `AudioTrack` has a `setPlaybackPositionUpdateListener` that would be cleaner than polling.
3. **No audio focus management.** `AudioStreamClient` creates `AudioTrack` directly with `USAGE_MEDIA` but never requests audio focus. If another app is playing audio (or `DispatchPlaybackService` is active), both will play simultaneously.
4. **Documented as a migration target.** The class-level comment acknowledges this is legacy and "a future migration will route replay through `DispatchPlaybackService` as well." This migration is the right call and should be prioritized.

---

### `ui/components/MiniPlayerBar.kt`

**What it does:**
A Compose composable that slides up from the bottom of the screen when `PlaybackUiState.isActive` is true. Shows sender avatar, sender name, message preview, queue count, and play/pause/skip/replay buttons.

**Connections:**
- Reads `PlaybackUiState` (passed as parameter, not directly observing `PlaybackStateHolder`)
- `onPlayPause`, `onSkipNext`, `onReplay` callbacks go back to the scaffold/screen that hosts it

**Issues flagged:**
1. **`onPlayPause`, `onSkipNext`, `onReplay` callbacks are wired to the UI but their implementations are not visible in this file.** From this audit's scope, it is unknown whether these callbacks correctly route to the `MediaSession` controller or directly to the service. If they bypass `MediaSession`, they will not integrate with system media controls.
2. **The skip next button appears only when `pendingCount > 1`**, but earbud double-tap always triggers voice reply regardless of queue state. The button and the gesture do different things. This may be intentional (skip vs. reply) but is not surfaced in the UI.
3. **The recording state** (`isRecording`, `replyTarget`) is rendered in the UI but, as noted above, the state is never set.

---

## 3. Audio Queue Architecture

The queue is split across two layers:

**Layer 1 — `DispatchPlaybackService.messageQueue` (ArrayDeque)**
An in-process, main-thread-only queue of `PendingMessage` objects. When a streaming item arrives and the player is busy, it goes here. When `STATE_ENDED` fires (or on error recovery), `playNextFromQueue()` drains one item. This is the correct approach for streaming sources because ExoPlayer's playlist cannot pre-buffer streaming HTTP POST responses.

**Layer 2 — ExoPlayer's internal playlist**
For WAV-file items (legacy path, status messages), multiple `MediaItem`s can coexist in ExoPlayer's playlist and transition automatically. For streaming items, the playlist is always cleared before each new item (`p.clearMediaItems()`), so ExoPlayer's queue is always length 1.

**Gap:** The `pendingCount` AtomicInteger is decremented in `PlayerEventListener.onPlaybackStateChanged(STATE_ENDED)` and also in `onMediaItemTransition`. For streaming items, only `STATE_ENDED` fires. For WAV-file items transitioning through ExoPlayer's playlist, `onMediaItemTransition` fires without `STATE_ENDED` (STATE_ENDED fires once at the very end). If a WAV item and a streaming item are in flight simultaneously (unlikely but possible via `playStatusMessage`), the counter bookkeeping could produce a wrong value.

---

## 4. FCM Voice Notification Flow

```
[Server] dispatch agent produces message text
    ↓
[Firebase] FCM high-priority data push to device
    ↓
DispatchFcmService.onMessageReceived()
  - Records VoiceNotification (sender, message, voice, thread, files)
  - Resolves voice: FCM payload voice field OR voiceForSender(sender)
  - Constructs spokenText = "$sender says: $messageText"
  - Calls startForegroundService(DispatchPlaybackService.createIntent(...))
  - Shows visible notification (NotificationCompat)
    ↓
DispatchPlaybackService.onStartCommand()
  - Extracts text, voice, sender, traceId, fcmReceiveTime from intent
  - Posts foreground notification immediately (compliance with 5s window)
  - Calls playbackState.onPlaybackStarted()
  - Calls queueStreamingItem()
    ↓
queueStreamingItem()
  - If player idle/ended: calls playStreamingItem() immediately
  - If player busy: pushes to messageQueue
    ↓
playStreamingItem()
  - Assigns UUID stream ID
  - Registers StreamRequest in streamRegistry (ConcurrentHashMap)
  - Creates MediaItem with tts://stream/{uuid} URI
  - Creates TtsStreamDataSource (DataSource.Factory)
  - Creates ProgressiveMediaSource (RawPcmExtractor.factory)
  - Calls p.clearMediaItems(), p.addMediaSource(), p.prepare(), p.play()
    ↓
TtsStreamDataSource.open()  [called by ExoPlayer on its internal thread]
  - Looks up stream ID in streamRegistry (ConcurrentHashMap, removes entry)
  - POSTs to TailscaleConfig.TTS_STREAM_SERVER (Kokoro streaming endpoint)
  - Reads and discards 44-byte WAV header
  - Returns C.LENGTH_UNSET (stream length unknown)
    ↓
TtsStreamDataSource.read()  [called repeatedly by ExoPlayer]
  - Reads bytes from Kokoro HTTP response stream
  - Passes bytes to RawPcmExtractor via ExoPlayer's internal pipeline
    ↓
RawPcmExtractor.read()
  - Assembles ~100ms PCM samples
  - Outputs sample metadata (timestamp, size)
  - Returns RESULT_CONTINUE until END_OF_INPUT
    ↓
ExoPlayer audio renderer decodes and plays PCM
  - Audio focus managed automatically by ExoPlayer (AudioAttributes set)
  - Headphone disconnect handled (setHandleAudioBecomingNoisy = true)
    ↓
PlayerEventListener.onPlaybackStateChanged(STATE_ENDED)
  - Decrements pendingCount
  - If messageQueue non-empty: calls playNextFromQueue()
  - If queue empty and count == 0: calls playbackState.onQueueEmpty()
```

**Latency budget (measured at code level):**
- FCM delivery: platform-controlled, typically 100–500ms
- `onStartCommand` → `playStreamingItem`: sub-millisecond (main thread)
- `TtsStreamDataSource.open()` → Kokoro first byte: network RTT + GPU generation startup, documented as ~500ms total
- First audio to ear: 500ms + buffering startup

---

## 5. Earbud Controls

Media button events from Bluetooth earbuds (Pixel Buds, generic BT HID) are routed by Android through the `MediaSession` to the registered `Player` implementation. The `DispatchForwardingPlayer` wraps ExoPlayer and intercepts two commands:

- `seekToNext()` — maps to double-tap on Pixel Buds — triggers voice reply flow
- `seekToPrevious()` — maps to triple-tap — currently reserved (no-op)
- `play()`, `pause()`, `setPlayWhenReady()` — passed through to ExoPlayer (single tap play/pause)

The earbud→MediaSession→ForwardingPlayer path is entirely inside `DispatchPlaybackService`. The ForwardingPlayer is registered on the `MediaSession` at `onCreate()`, so it intercepts all media button events for the lifetime of the service.

**Issues:**
1. **Voice reply always targets the most recent message sender** (`voiceNotificationRepository.getAtCursor()`). If the user is listening to message N from the queue but message N+1 arrived more recently, the cursor points to N+1, so the reply goes to the wrong sender. The cursor is reset to 0 on every `VoiceNotificationRepository.add()` call, meaning the cursor always points to the newest message regardless of what is currently playing.
2. **The `VOICE_CUE_DELAY_MS = 2500L` fixed delay** before starting speech recognition does not account for message length. A short status cue ("Reply to boardroom. Go ahead.") takes less than 2 seconds. A longer TTS cue would take more. The mic opens before or after the cue finishes based on timing luck rather than audio completion state.
3. **No cancellation path for voice reply.** If the user initiates a double-tap and decides not to reply, they cannot cancel the recognition session except by staying silent (which triggers `ERROR_SPEECH_TIMEOUT`).

---

## 6. Media3 / ExoPlayer Session Management

`DispatchPlaybackService` extends `MediaSessionService` and creates one `ExoPlayer` and one `MediaSession` in `onCreate()`. The session is exposed via `onGetSession()`. Media3 handles:

- Foreground notification (MediaStyle) automatically via `DefaultMediaNotificationProvider`
- Audio focus request and abandonment
- `ACTION_AUDIO_BECOMING_NOISY` broadcast (headphone unplug)
- Bluetooth media button routing via `MediaSession`
- Android 15 foreground service compliance (mediaPlayback type declared in manifest)

The service is `START_STICKY`, meaning Android will restart it after process death. On restart with no intent data, it posts an idle notification and returns immediately. ExoPlayer is recreated fresh — there is no state restoration.

**Issues:**
1. **Two notification channels for one service.** `DOWNLOAD_CHANNEL_ID` ("dispatch_download") and `PLAYBACK_CHANNEL_ID` ("dispatch_playback") are both created. The service starts with the download channel notification, then Media3 takes over with the playback channel. Users see both channels in notification settings. In streaming mode (the primary path), the "downloading" phase is essentially zero-duration, so the download channel notification is shown for milliseconds before Media3 replaces it. The download channel is vestigial and should be removed.
2. **`cleanTempFiles()` deletes files matching `dispatch_*.wav` in `cacheDir`** on service destroy. This is correct for the legacy WAV download path. The streaming path never creates temp files, so this is a no-op in the primary path. Should be kept for correctness but documented clearly.
3. **`MediaSession.Builder` does not set a `callback`.** Without a session callback, `MediaSession` uses default command handling. Custom commands (e.g., a "reply" action from a notification button) cannot be received. If a future notification action is added, a callback will be needed.

---

## 7. Foreground Service Notification

Three services use foreground notifications:

| Service | Channel | Importance | Type |
|---|---|---|---|
| `SseConnectionService` | `sse_connection` | MIN (silent) | dataSync |
| `DispatchPlaybackService` (idle/streaming transition) | `dispatch_download` | LOW | mediaPlayback |
| `DispatchPlaybackService` (playing, via Media3) | `dispatch_playback` | LOW | mediaPlayback |

The `SseConnectionService` notification is correctly MIN importance — silent persistent indicator. The `DispatchPlaybackService` notifications are LOW importance — no sound, no heads-up, no badge. This is correct behavior for a media player.

**Issues:**
1. **The "Streaming from {sender}..." notification** shown during the streaming startup window (the milliseconds before ExoPlayer has media) uses `android.R.drawable.ic_media_play`, a system generic icon. The Media3 auto-notification uses the same fallback. A custom Dispatch notification icon is referenced in the `TODO` comment in `DispatchFcmService` but not yet implemented.
2. **The download channel notification** (`dispatch_download`) will appear in the user's notification settings as a separate channel even though users will rarely see it. The channel name "Dispatch Downloading" is confusing if users never see messages being downloaded (streaming replaces download).

---

## 8. StreamingTtsQueue — Streaming Chat Path

When a user sends a message via `MessagesViewModel.sendStreaming`, the File Bridge SSE stream sends back `sentence` events as the Claude agent produces complete sentences. The ViewModel calls `streamingTtsQueue.enqueue(sentence, department)`, which fires a `startForegroundService` intent for each sentence.

This means for a 5-sentence response, 5 separate `startForegroundService` intents fire in rapid succession. Each intent adds a `PendingMessage` to `DispatchPlaybackService.messageQueue` (if the player is busy), or plays immediately (if idle). The service serializes playback: sentence 1 plays, STATE_ENDED fires, sentence 2 plays, and so on.

**Issues:**
1. **Each sentence is a `startForegroundService` call.** Five sentences = five foreground service starts. Android logs these and they add latency. A more efficient design would have `StreamingTtsQueue` hold its own sentence queue and communicate via a single persistent channel to the service (Binder, AIDL, or shared `Channel`).
2. **`streamingTtsQueue.reset()` is called at the start of `sendStreaming`**, but if the previous stream is still playing (the user sends a new message while the previous response is mid-sentence), the new sentences will interleave with the old ones in `messageQueue`. There is no cancellation of in-flight streaming TTS.
3. **`fcmReceiveTime = 0L` is passed for streaming sentences.** This disables latency tracing for the streaming chat path. The trace ID is also null. The logging granularity is missing for this path.

---

## 9. Architectural Issues Summary

### Critical
- **Two download implementations** (`DispatchPlaybackService.downloadWav` and `AudioStreamClient.downloadWav`) with duplicated constants, retry logic, and error handling. Extract to `KokoroTtsDownloader`.
- **Two voice mapping tables** (`DispatchFcmService.voiceForSender` and `StreamingTtsQueue.voiceForDepartment`) with contradictory entries. Extract to a `VoiceMapper` singleton.
- **`AudioStreamClient` has no audio focus management** and cannot coexist cleanly with `DispatchPlaybackService`. UI replay can interrupt or be interrupted by FCM audio.
- **`onVoiceReplyStarted`/`onVoiceReplyEnded` on `PlaybackStateHolder` are never called**, making the recording state in the MiniPlayerBar permanently invisible.

### High
- **`DispatchPlaybackService` is a god class** (~900 lines, 8+ responsibilities). The speech recognition lifecycle, voice reply HTTP send, and status TTS should move out.
- **Voice reply cursor always points to the newest message**, not the currently playing message. Double-tap reply to the wrong sender is a latent bug for rapid multi-message scenarios.
- **`VOICE_CUE_DELAY_MS` is a hard-coded guess** for audio cue duration. Should wait for ExoPlayer `STATE_ENDED` or `onIsPlayingChanged(false)` instead.
- **Streaming chat TTS fires one `startForegroundService` per sentence** instead of using a persistent channel. This is chatty and does not support cancellation.
- **No cancellation path for in-flight streaming TTS** when a new `sendStreaming` call is made.
- **`exported="true"` on `DispatchPlaybackService`** without a custom permission allows any app to start audio playback.

### Medium
- **`tapHandler` and `pendingSingleTap`** are declared but unused. Dead code.
- **Auto-resume on stale pause** overrides user intent when a new message arrives.
- **Model version check missing in `ModelManager`** — stale Piper models will persist across app updates.
- **Busy-polling in `TtsEngine.initPiperWhenReady`** blocks the executor during model extraction.
- **`dispatch_download` notification channel** is vestigial now that streaming is the primary path.
- **`waitForDrain` polling loop in `AudioStreamClient`** should use a listener instead.
- **Hardcoded subject line** "Voice reply from Nigel" in `VoiceReplyCoordinator`.
- **`pendingCount` double-decrement risk** if WAV items and streaming items are both in flight.

### Low
- **`reset()` in `StreamingTtsQueue` does nothing** — misleading for callers.
- **`System.currentTimeMillis().toInt()` for notification IDs** in `DispatchFcmService` — use an `AtomicInteger` counter.
- **`offlineTts: Any?`** in `TtsEngine` erases type safety. A sealed interface or lazy wrapper would be cleaner.
- **RawPcmExtractor hard-codes 24kHz/mono/16-bit as defaults** with no server-side format negotiation.
- **`TtsEngine` initializes Piper model at app startup** even when GPU TTS is always available.

---

## 10. Missing Abstractions

| Missing | Where Needed | Impact |
|---|---|---|
| `KokoroTtsDownloader` | `DispatchPlaybackService`, `AudioStreamClient` | Eliminates download duplication |
| `VoiceMapper` | `DispatchFcmService`, `StreamingTtsQueue`, possibly `AudioStreamClient` | Single source of truth for sender→voice |
| `VoiceReplySession` | `DispatchPlaybackService` | Extracts speech recognition lifecycle |
| `PlayingMessageTracker` | `DispatchPlaybackService`, `VoiceNotificationRepository` | Tracks currently-playing message ID for correct reply targeting |
| Cancellable `StreamingTtsSession` | `MessagesViewModel`, `StreamingTtsQueue` | Supports cancel on new send, exposes streaming-sentence latency tracing |
| `MediaSessionController` wrapper | `MiniPlayerBar` host screen | Ensures UI buttons route through `MediaSession` not direct intents |

---

## 11. What Is Working Well

- The streaming TTS pipeline (FCM → Service → TtsStreamDataSource → RawPcmExtractor → ExoPlayer) is architecturally sound and well-reasoned. The comments explaining why `WavExtractor` cannot be used and why multiple streaming items cannot go into ExoPlayer's playlist are accurate and save future developers from re-investigating.
- Media3 adoption is correct. Audio focus, headphone disconnect, Bluetooth routing, and foreground service compliance are all handled without bespoke code.
- `VoiceReplyCoordinator` is a clean boundary — the service does not touch cmail internals.
- `PlaybackStateHolder` as a singleton state bridge between service and UI avoids `bindService` complexity while maintaining reactivity.
- Trace ID propagation from FCM receive through ExoPlayer queue to playback completion is thorough and enables latency debugging via SSH log tail.
- The `BaseDataSource` contract implementation in `TtsStreamDataSource` is correct and documented with references.
- `RawPcmExtractor`'s 10-samples/second pacing matches WavExtractor's internal behavior, preventing buffer stall.
- `SseConnectionService` correctly uses exponential backoff, `Last-Event-ID` for resumability, and a WakeLock scoped only to reconnection delays.
