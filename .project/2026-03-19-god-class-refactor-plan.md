# God Class Refactor Plan

**Date:** 2026-03-19
**Trigger:** detekt TooManyFunctions violations in two service classes
**Scope:** Research + plan only. No edits to source files.

---

## DispatchAccessibilityService (44 functions)

### Current State

`DispatchAccessibilityService` is an `AccessibilityService` that acts as a remote automation agent: it receives `PhoneAction` commands from `DispatchFcmService`, launches system intents (dialer, Google Messages via `smsto:` URI), then uses the accessibility tree to locate and interact with UI elements — tapping call/send buttons, populating compose fields, scraping message threads, and dumping the full accessibility tree to JSON for diagnostic use. The class also owns all retry orchestration (timer-based, 12-attempt cap), result serialization to shared storage (`/Download/dispatch-logs/`), and notification posting. It runs on a handler attached to the main looper and is self-contained: there is no repository, no ViewModel, and no DI injection.

**Function count breakdown by responsibility area:**

| Area | Functions | Notes |
|---|---|---|
| Lifecycle | 4 | `onServiceConnected`, `onAccessibilityEvent`, `onInterrupt`, `onDestroy` |
| Action dispatch | 3 | `handleAction`, `launchIntent`, `tryExecutePendingAction` |
| Root node finding | 3 | `isCorrectAppRoot`, `findTargetAppRoot`, `findWindowByPackage` |
| Call automation | 1 | `tryTapCallButton` |
| SMS automation | 2 | `tryTapSendButton`, `findComposeField` |
| Read-messages automation | 3 | `handleReadMessages`, `tapConversationNode`, `findConversationByContact` |
| Message extraction | 5 | `extractStructuredMessages`, `parseMessageDesc`, `extractMessageText`, `isUiChrome`, `findScrollableContainer` |
| Tree dump (diagnostic) | 4 | `handleDumpTree`, `dumpAllWindows`, `writeDumpFile`, `dumpTreeToJson` |
| Node search helpers | 6 | `findNodeBySystemSearch`, `findNodeByDescriptionDeep`, `findNodeByContentDescription`, `findNodeByResourceId`, `findClickableByText`, `findNodeByTextContaining` |
| Tap execution | 2 | `tapNode`, `gestureClick` |
| Retry / result | 3 | `retryOrGiveUp`, `actionSuccess`, `actionFailed` |
| Cleanup / state | 2 | `cleanup`, `findClickableAncestor` |
| App launch helpers | 2 | `getLaunchIntent`, `knownLauncherActivities` (property) |
| Notifications | 2 | `showNotification`, `createNotificationChannel` |
| Debug logging | 1 | `dumpTree` (Timber-only variant) |
| Companion statics | 2 | `isEnabled`, `executeAction` |

**Total: ~44 functions** across ~1,400 lines.

---

### API/Library Audit

#### Target API compatibility

- `compileSdk = 36`, `targetSdk = 35`, `minSdk = 26`
- `AccessibilityService` itself is not deprecated and remains the correct mechanism for apps that legitimately need cross-app UI automation. The service is properly declared with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` and a `<meta-data>` XML config — both required by the framework.
- `android:isAccessibilityTool="true"` is set in `accessibility_service_config.xml`. This attribute (added in API 31) marks the service as a genuine assistive tool, which is important for Play Store compliance review and for opting out of restrictions that apply to automation-only services.
- No `foregroundServiceType` is declared on `DispatchAccessibilityService` in the manifest. `AccessibilityService` is a bound system service — it is not started with `startForegroundService()` and does not need `foregroundServiceType`. This is correct; the type only matters for services the app itself starts in the foreground.

#### Deprecated APIs

- `AccessibilityNodeInfo.recycle()` — deprecated in API 33. On API 33+ the framework manages node lifecycle automatically. The code calls `recycle()` extensively, guarded by `try/catch`. This is safe but generates noise. Post-API-33 devices ignore the call; it is not harmful.
- `Environment.getExternalStoragePublicDirectory()` paired with `android.permission.MANAGE_EXTERNAL_STORAGE` — this is the broadest possible storage permission and flagged by Play Store policy as requiring special justification. Result files are written to `/Download/dispatch-logs/`. This is only used for diagnostic output (`DumpTree`, `ReadMessages` result files), not core functionality.

#### Android 17 impact

Android 17 (API 37, in preview as of March 2026) introduces **restrictions on `AccessibilityService` used for non-accessibility purposes** by non-system apps. The key change is: apps targeting API 37+ that use `AccessibilityService` for purposes other than assisting users with disabilities face stricter review and may need to use `AppFunctions` instead for agent-style automation.

**However:** This app already has `appfunctions`, `appfunctions-service`, and `appfunctions-compiler` declared as dependencies in `build.gradle.kts`, and `android:isAccessibilityTool="true"` is set — indicating the team is aware of the shift. The `targetSdk` is 35, not 37, so Android 17 restrictions do not apply yet. When `targetSdk` is bumped to 37, the automation use cases (call, text, read messages) will need to be re-evaluated against the AppFunctions model. The `DumpTree` diagnostic action is clearly developer tooling and lowest risk.

**Practical implication:** `AccessibilityService` remains viable today. The refactor should not replace it with AppFunctions now — that is a separate, larger migration that only becomes required when targeting API 37.

#### Missing best practices

1. **No `foregroundServiceType="accessibility"` in manifest** — for `AccessibilityService` this is not needed (it is bound by the system, not started by the app). Correct as-is.
2. **Static singleton instance pattern** (`companion object { var instance }`) — this is the standard pattern for `AccessibilityService` since Android forbids instantiating it directly. The `@Volatile` annotation is present. Correct.
3. **Handler-based retry instead of coroutines** — intentional: `AccessibilityService` callbacks run on the main thread, and coroutine scope would require lifecycle management. The handler approach is idiomatic for this type of service.
4. **No `ServiceInfo.FLAG_INPUT_METHOD_EDITOR` or `FLAG_ENABLE_ACCESSIBILITY_VOLUME`** set — not needed for this use case.
5. **`onAccessibilityEvent` is a no-op** (intentionally, per comment explaining retry-burn fix). This is correct and well-documented in the code.

---

### Refactor Plan

The class has 44 functions because it mixes five genuinely distinct responsibilities. The detekt violation is legitimate — not just cosmetic.

#### What to extract into separate classes

**1. `AccessibilityNodeFinder` (utility object)**

Extract all tree-search helpers — they are pure functions with no service state:
- `findNodeBySystemSearch`
- `findNodeByContentDescription`
- `findNodeByResourceId`
- `findClickableByText`
- `findNodeByDescriptionDeep`
- `findNodeByTextContaining`
- `findClickableAncestor`
- `findScrollableContainer`
- `findWindowByPackage`
- `findTargetAppRoot`
- `isCorrectAppRoot`

These should become `internal object AccessibilityNodeFinder` with functions taking `AccessibilityNodeInfo` and returning `AccessibilityNodeInfo?`. No service reference needed. This removes ~11 functions from the service.

**2. `AccessibilityTreeSerializer` (utility object)**

Extract tree-dump and JSON serialization:
- `dumpTreeToJson`
- `dumpTree` (Timber debug variant)

These are pure recursive functions. No service state needed. ~2 functions removed.

**3. `MessageExtractionHelper` (utility object or class)**

Extract all message reading and parsing logic:
- `extractStructuredMessages`
- `extractMessageText`
- `parseMessageDesc`
- `isUiChrome`

Returns structured `List<JSONObject>` or `List<String>`. No service state. ~4 functions removed.

**4. `ActionResultWriter` (class, takes a `Context`)**

Extract all result/dump file I/O:
- `writeResultFile`
- `writeStructuredResultFile`
- `writeDumpFile`
- `dumpAllWindows` (the write half — serialization stays in `AccessibilityTreeSerializer`)

Takes a `Context` for `Environment.getExternalStoragePublicDirectory`. ~4 functions removed. This is also where the `MANAGE_EXTERNAL_STORAGE` justification lives — isolating it makes the permission audit cleaner.

**5. `ActionNotificationManager` (class, takes a `Context`)**

Extract notification channel creation and posting:
- `createNotificationChannel`
- `showNotification`

~2 functions removed.

#### What stays in `DispatchAccessibilityService`

After extraction the service retains only:
- Lifecycle: `onServiceConnected`, `onAccessibilityEvent`, `onInterrupt`, `onDestroy`
- Action orchestration: `handleAction`, `launchIntent`, `tryExecutePendingAction`, `retryOrGiveUp`, `actionSuccess`, `actionFailed`, `cleanup`
- Action handlers (thin, delegating): `tryTapCallButton`, `tryTapSendButton`, `findComposeField`, `handleReadMessages`, `handleDumpTree`, `tapConversationNode`
- Tap execution: `tapNode`, `gestureClick`
- App launch: `getLaunchIntent`
- Companion statics: `isEnabled`, `executeAction`

Estimated remaining function count: ~22-24. Under the detekt threshold.

#### Proposed class structure

```
accessibility/
  DispatchAccessibilityService.kt       (~22 functions, orchestration only)
  PhoneAction.kt                        (unchanged)
  AccessibilityNodeFinder.kt            (internal object, 11 search helpers)
  AccessibilityTreeSerializer.kt        (internal object, 2 dump/serialize functions)
  MessageExtractionHelper.kt            (internal object, 4 extraction functions)
  ActionResultWriter.kt                 (class with Context, 4 write functions)
  ActionNotificationManager.kt          (class with Context, 2 notification functions)
```

#### Migration steps

1. Create `AccessibilityNodeFinder` as `internal object`. Move all pure search functions. Update all call sites in `DispatchAccessibilityService` to use qualified calls (e.g., `AccessibilityNodeFinder.findNodeByResourceId(...)`). Run tests / manual smoke test of call + text actions.

2. Create `AccessibilityTreeSerializer` as `internal object`. Move `dumpTreeToJson` and the Timber `dumpTree`. Update `handleDumpTree` and `tryTapCallButton`/`tryTapSendButton` (debug dump calls).

3. Create `MessageExtractionHelper` as `internal object`. Move the four extraction/parsing functions. Update `handleReadMessages`.

4. Create `ActionResultWriter(context: Context)`. Instantiate lazily in `onServiceConnected`. Move the four file-write functions. Update `handleReadMessages` and `handleDumpTree`.

5. Create `ActionNotificationManager(context: Context)`. Instantiate lazily in `onServiceConnected`. Move `createNotificationChannel` and `showNotification`. Update `onServiceConnected`, `actionSuccess`, `actionFailed`.

6. Run detekt. Verify `TooManyFunctions` is cleared on `DispatchAccessibilityService`. Verify no new violations in extracted classes (they should all be under 10 functions each).

7. **Do not** add DI (Hilt) to these helper classes — they are package-private utilities, not injectable services. Keeping them as `internal object` or simple classes avoids DI boilerplate for code that has no testability benefit from injection.

---

## DispatchPlaybackService (20 functions)

### Current State

`DispatchPlaybackService` extends Media3 `MediaSessionService`. It manages the full lifecycle of streaming TTS audio playback: it receives intents from `DispatchFcmService` and `StreamingTtsQueue`, POSTs to a Kokoro TTS server via OkHttp, streams the response through a custom `TtsStreamDataSource` → `RawPcmExtractor` → `ExoPlayer` pipeline, and maintains a manual `messageQueue` (`ArrayDeque<PendingMessage>`) for serialized playback of messages that arrive while the player is busy. It also handles earbud double-tap via `DispatchForwardingPlayer`, voice reply via `SpeechRecognizer`, and status message playback (a separate download-then-queue codepath using the non-streaming WAV endpoint). Two OkHttpClient instances exist: one short-timeout (`httpClient`) for status WAV downloads, one long-timeout (`streamingClient`) for streaming.

**Function count breakdown:**

| Area | Functions |
|---|---|
| Lifecycle | 3 | `onCreate`, `onGetSession`, `onDestroy` |
| Intent handling | 1 | `onStartCommand` |
| Streaming queue | 3 | `queueStreamingItem`, `playStreamingItem`, `playNextFromQueue` |
| Legacy WAV download | 2 | `downloadWav`, `attemptDownload` |
| Legacy WAV queue | 1 | `queueMediaItem` |
| DataSource (inner class) | 3 | `open`, `read`, `close` (in `TtsStreamDataSource`) |
| Player event listener (inner class) | 4 | `onPlaybackStateChanged`, `onPlayerError`, `onIsPlayingChanged`, `onMediaItemTransition` |
| ForwardingPlayer (inner class) | 2 | `seekToNext`, `seekToPrevious` |
| Voice reply | 3 | `startVoiceReply`, `launchSpeechRecognition`, `sendVoiceReply` |
| Status message | 1 | `playStatusMessage` |
| Notifications | 2 | `buildDownloadNotification`, `createDownloadNotificationChannel` |
| Utility | 3 | `onMessageComplete`, `flushLogs`, `cleanTempFiles` |

**Total: ~28 countable method definitions** across the service and its inner classes. The detekt `TooManyFunctions` count of 20 likely counts only the top-level class methods (excluding inner class functions from the threshold), which means the class body itself has exactly 20+ declared functions.

---

### API/Library Audit

#### Media3 usage correctness

**Version in use:** `media3 1.9.2` (as stated in the task; this is very recent — released ~early 2026).

**MediaSessionService pattern:** Correct. The service extends `MediaSessionService`, declares `android:foregroundServiceType="mediaPlayback"` in the manifest, registers the `androidx.media3.session.MediaSessionService` intent filter, holds `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission, and calls `super.onStartCommand()` before any custom logic. This matches the canonical pattern confirmed by Context7.

**ForwardingPlayer for earbud controls:** Correct. `DispatchForwardingPlayer` extends `ForwardingPlayer(exoplayer)` and overrides `seekToNext()` (double-tap) and `seekToPrevious()` (triple-tap). The `ForwardingPlayer` is passed to `MediaSession.Builder`, so the session routes media button events through it. This is the correct Media3 pattern for intercepting skip commands without replacing the player entirely.

**Audio focus:** Correct. `ExoPlayer.Builder` is called with:
```kotlin
.setAudioAttributes(..., /* handleAudioFocus= */ true)
.setHandleAudioBecomingNoisy(true)
```
This delegates audio focus and headphone-disconnect handling entirely to Media3/ExoPlayer, which is the recommended approach — manual `AudioManager.requestAudioFocus()` is the legacy pattern (still shown in Context7 docs for `MediaSessionCompat`). The new Media3 stack handles this automatically when these flags are set.

**Custom DataSource (`TtsStreamDataSource`):** Correct and well-implemented. It extends `BaseDataSource`, calls `transferInitializing()` before I/O, `transferStarted()` after successful open, `bytesTransferred()` per read, and `transferEnded()` in `close()`. This satisfies the full `BaseDataSource` contract. The WAV header stripping (44 bytes) is intentional and documented — it works around a Kokoro server quirk where the WAV data-size field is set to `0x7FFFFF00`, which would cause `WavExtractor` to miscalculate remaining bytes. Using `RawPcmExtractor` instead is the correct fix.

**Custom Extractor (`RawPcmExtractor`):** Correct. It follows the `WavExtractor` PassthroughOutputWriter pattern — accumulates ~100ms of samples before emitting, handles `RESULT_END_OF_INPUT` cleanly with a partial-sample flush. The `SeekMap.Unseekable` + `C.TIME_UNSET` is correct for a live stream.

**Manual message queue vs. ExoPlayer playlist:** The `messageQueue: ArrayDeque<PendingMessage>` pattern exists because ExoPlayer eagerly pre-buffers the next playlist item's `DataSource`. Since `TtsStreamDataSource` consumes the HTTP response stream on `open()`, the response is gone by the time ExoPlayer would play the second item. The serial queue (play one, wait for `STATE_ENDED`, play next) is the correct architectural response to this constraint. This is not a bug — it is the only viable approach without switching to a file-based pipeline (which reintroduces the download latency the streaming approach eliminates).

**`DefaultMediaNotificationProvider`:** Used with `NOTIFICATION_ID` and `PLAYBACK_CHANNEL_ID`. This is correct usage — `setMediaNotificationProvider()` must be called in `onCreate()` before the service is made foreground, which it is. The "download" notification (`buildDownloadNotification`) is a temporary notification posted via `startForeground()` to satisfy the Android foreground service requirement before ExoPlayer has any media items to drive the Media3 auto-notification.

#### Foreground service compliance

- `android:foregroundServiceType="mediaPlayback"` in manifest: correct for Android 10+.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission: present in manifest.
- `startForeground()` is called immediately in `onStartCommand` regardless of path (both the null-text case and the normal case). This is required on Android 14+ where `startForegroundService()` callers must call `startForeground()` within 10 seconds or face `ForegroundServiceDidNotStartInTimeException`.

#### Audio focus handling

Delegated entirely to Media3 via `handleAudioFocus = true`. No manual `AudioManager` calls. Correct for Media3 1.x.

#### Earbud control patterns

The `DispatchForwardingPlayer` → `MediaSession` chain is correct. Single-tap play/pause is handled natively by ExoPlayer. Double-tap (skip-next) is intercepted and routes to `startVoiceReply()`. The 2500ms `VOICE_CUE_DELAY_MS` before launching `SpeechRecognizer` gives the status TTS message time to play. The tap debounce uses `Handler.postDelayed` — this is functional but could cause issues if earbud firmware sends multiple events quickly. No earbud-specific `tapHandler` debounce is currently active (the field `tapHandler` and `pendingSingleTap` are declared but not used in the current code).

#### Issues found

1. **Dead code: `tapHandler` and `pendingSingleTap`** — declared at lines 117-118 but never used. The single-tap debounce was apparently planned but not implemented. These fields should be removed or the debounce implemented.

2. **Legacy download path still present** — `downloadWav()` and `attemptDownload()` exist for status messages (`playStatusMessage`). This is intentional (status messages use the non-streaming endpoint), but the `queueMediaItem()` function that handles file-based WAV items is only called from `playStatusMessage`. The legacy WAV path for incoming FCM messages appears to have been fully replaced by the streaming path, but `downloadWav`/`attemptDownload`/`queueMediaItem` are kept for status audio.

3. **`onMessageComplete()` is never called** — the function at line 582 decrements `pendingCount` and calls `playbackState.onQueueEmpty()`, but it is not called from `PlayerEventListener.onPlaybackStateChanged` (which handles `STATE_ENDED` directly). This is dead code, or it was meant to replace the inline logic in the event listener. Should be reconciled.

4. **`SpeechRecognizer` created in a `MediaSessionService` context** — `SpeechRecognizer.createSpeechRecognizer(this)` in a Service works but `SpeechRecognizer` is not designed for long-lived use in a background service. It requires the UI thread (already satisfied — called from `Handler(mainLooper).postDelayed`). The recognizer is destroyed in all callback paths. This is functional for now.

5. **`pendingCount` double-decrements possible** — `PlayerEventListener.onMediaItemTransition` (for `REASON_AUTO`) decrements `pendingCount`, AND `onPlaybackStateChanged(STATE_ENDED)` also decrements it. For the streaming path, `STATE_ENDED` fires when the streaming item finishes, but `MEDIA_ITEM_TRANSITION_REASON_AUTO` fires when transitioning between playlist items (which the streaming path does not use — it manually calls `clearMediaItems()` and `addMediaSource()`). In practice, the streaming path only triggers `STATE_ENDED`, so the double-decrement may not occur, but this is subtle and should be verified.

---

### Refactor Plan

The 20-function count is borderline. Five of those functions are in inner classes (`TtsStreamDataSource`, `PlayerEventListener`, `DispatchForwardingPlayer`) which could legitimately be promoted to top-level package-private classes. The voice reply subsystem is the clearest extract candidate.

#### What to extract into separate classes

**1. Promote `TtsStreamDataSource` to a top-level package-private class**

`TtsStreamDataSource` is an `inner class` only because it needs access to `streamRegistry` and `streamingClient`. If `streamRegistry` and `streamingClient` are passed as constructor parameters, it can become a standalone `internal class TtsStreamDataSource`. This removes 3 functions from the service's inner-class count and makes the DataSource independently testable (it currently cannot be unit tested as an inner class).

```kotlin
// New signature:
internal class TtsStreamDataSource(
    private val streamRegistry: ConcurrentHashMap<String, StreamRequest>,
    private val streamingClient: OkHttpClient,
) : BaseDataSource(true) { ... }
```

**2. Extract `VoiceReplyController`**

Voice reply is a self-contained subsystem with no dependency on ExoPlayer state or the message queue. It only needs:
- A `Context` (for `SpeechRecognizer`)
- A `VoiceNotificationRepository` (to get last sender)
- A `VoiceReplyCoordinator` (to POST the reply)
- A callback to play status messages back through the player

Extract: `startVoiceReply`, `launchSpeechRecognition`, `sendVoiceReply`. Move to `VoiceReplyController(context, voiceNotificationRepository, voiceReplyCoordinator, onStatusMessage: (String) -> Unit)`.

This removes 3 functions from the service. It also makes the voice reply subsystem independently testable.

**3. Extract `PlaybackNotificationManager`**

Extract: `buildDownloadNotification`, `createDownloadNotificationChannel`. These are pure notification-building functions with no player state. Move to `internal class PlaybackNotificationManager(context: Context)`. Removes 2 functions.

**4. Promote `PlayerEventListener` to top-level**

`PlayerEventListener` is an `inner class` that references `pendingCount`, `messageQueue`, `playbackState`, and `this@DispatchPlaybackService`. These references prevent straightforward promotion. Instead, pass them as constructor parameters or callbacks:

```kotlin
internal class DispatchPlayerEventListener(
    private val pendingCount: AtomicInteger,
    private val messageQueue: ArrayDeque<PendingMessage>,
    private val playbackStateHolder: PlaybackStateHolder,
    private val onPlayNext: () -> Unit,
    private val onAllComplete: () -> Unit,
    private val onFlushLogs: () -> Unit,
) : Player.Listener { ... }
```

Removes 4 functions from the service's inner-class count.

**5. Promote `DispatchForwardingPlayer` to top-level**

`DispatchForwardingPlayer` only needs a callback reference (`onDoubleTap: () -> Unit`):

```kotlin
internal class DispatchForwardingPlayer(
    player: ExoPlayer,
    private val onDoubleTap: () -> Unit,
) : ForwardingPlayer(player) { ... }
```

Removes 2 functions.

#### What stays in `DispatchPlaybackService`

After extraction:
- Lifecycle: `onCreate`, `onGetSession`, `onStartCommand`, `onDestroy`
- Queue management: `queueStreamingItem`, `playStreamingItem`, `playNextFromQueue`
- Legacy WAV path: `downloadWav`, `attemptDownload`, `queueMediaItem`, `playStatusMessage`
- Utility: `flushLogs`, `cleanTempFiles`
- Companion: `createIntent`

Estimated remaining function count: ~14. Well under threshold.

#### Cleanup items to address during refactor

- Remove dead `tapHandler` and `pendingSingleTap` fields.
- Remove or integrate `onMessageComplete()` (dead function).
- Audit `pendingCount` for double-decrement in `onMediaItemTransition` vs `onPlaybackStateChanged(STATE_ENDED)`. Likely safe to remove `onMediaItemTransition` logic since the streaming path never uses auto-transition.

#### Proposed class structure

```
playback/
  DispatchPlaybackService.kt        (~14 functions, orchestration only)
  StreamingTtsQueue.kt              (unchanged)
  RawPcmExtractor.kt                (unchanged)
  TtsStreamDataSource.kt            (promoted from inner class, independently testable)
  VoiceReplyController.kt           (extracted voice reply subsystem)
  DispatchPlayerEventListener.kt    (promoted from inner class)
  DispatchForwardingPlayer.kt       (promoted from inner class)
  PlaybackNotificationManager.kt    (extracted notification helper)
```

All new files stay in the `dev.digitalgnosis.dispatch.playback` package. No package restructuring needed.

#### Migration steps

1. **Fix dead code first:** Remove `tapHandler`, `pendingSingleTap`. Reconcile `onMessageComplete` vs inline `STATE_ENDED` handling. Audit `pendingCount` double-decrement. Commit these as a cleanup pass before the structural refactor.

2. **Promote `TtsStreamDataSource`:** Move to its own file. Add `streamRegistry` and `streamingClient` as constructor params. Update `playStreamingItem` to instantiate `TtsStreamDataSource(streamRegistry, streamingClient)`. Run smoke test (streaming message plays end-to-end).

3. **Promote `DispatchForwardingPlayer`:** Move to its own file with callback param. Update `onCreate` instantiation. Run earbud test (double-tap triggers voice reply).

4. **Extract `PlaybackNotificationManager`:** Move two notification functions. Instantiate in `onCreate`. Update `onStartCommand` and `createDownloadNotificationChannel` call site.

5. **Promote `PlayerEventListener`:** Define callback interface or use lambdas. Pass `pendingCount`, `messageQueue`, `playbackStateHolder`, and three callbacks (`onPlayNext`, `onAllComplete`, `onFlushLogs`). Move to own file. Update `exoPlayer.addListener(...)` call in `onCreate`.

6. **Extract `VoiceReplyController`:** Move three voice reply functions. Wire `onStatusMessage = { text -> playStatusMessage(text) }` as callback to avoid circular dependency. Update `DispatchForwardingPlayer.onDoubleTap` callback to delegate to `voiceReplyController.start()`.

7. Run detekt. Verify `TooManyFunctions` is cleared on `DispatchPlaybackService`. Confirm no threshold violations in extracted classes.

8. Write unit tests for `TtsStreamDataSource` (mock `streamRegistry`, assert `open`/`read`/`close` lifecycle). This was previously untestable as an inner class.

---

## Summary Table

| Class | Current functions | Target | Primary extracts |
|---|---|---|---|
| `DispatchAccessibilityService` | 44 | ~22 | `AccessibilityNodeFinder`, `MessageExtractionHelper`, `AccessibilityTreeSerializer`, `ActionResultWriter`, `ActionNotificationManager` |
| `DispatchPlaybackService` | 20 | ~14 | `TtsStreamDataSource` (promote), `VoiceReplyController`, `DispatchPlayerEventListener` (promote), `DispatchForwardingPlayer` (promote), `PlaybackNotificationManager` |

Neither class requires a change in architecture, API approach, or library version. Both use the correct APIs for their target SDK. The refactors are structural decomposition only — no behavior changes.
