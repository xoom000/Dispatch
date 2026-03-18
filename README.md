# Dispatch -- DG Voice Intercom (Android)

Bilateral voice intercom and agent command center for Digital Gnosis. Agents send messages via the `dispatch` CLI -- the app receives them over FCM, speaks them aloud using GPU-powered Kokoro TTS, and logs them to a persistent message list. Nigel sends messages back to any department through the cmail message bus via File Bridge. The app also surfaces cmail threads, live agent sessions, and flight recorder telemetry -- all from the phone.

## How It Works (v3.0)

### Inbound (Agents -> Nigel)
1. Agent sends message via `dispatch` CLI -> FCM data message
2. `DispatchFcmService` receives it (background or foreground)
3. Message persisted to Room database via `MessageRepository`
4. Starts `AudioPlaybackService` as foreground service (survives background execution limits)
5. `AudioStreamClient` POSTs to Oasis GPU (Kokoro TTS) -> downloads WAV -> strips 44-byte header -> plays raw PCM
6. If GPU unreachable: retries once (2s delay for Tailscale cold-start), then falls back to on-device Piper TTS
7. Low-priority notification posted as visual breadcrumb
8. If FCM includes `thread_id`, emits thread refresh event for instant UI update
9. Service stops itself when all queued audio completes

### Outbound (Nigel -> Agents)
1. Nigel selects department(s) from searchable picker in Send tab
2. Types message, optionally attaches files
3. App uploads files to File Bridge, then POSTs to `/cmail/send` with invoke flag
4. File Bridge relays through cmail -> department inbox + agent invocation
5. Outgoing message appears in Messages tab with distinct styling
6. If agent was invoked, Watch button appears for live session monitoring

## FCM Payload

Data-only message (no notification block):

```json
{
  "data": {
    "sender": "engineering",
    "message": "Deployment finished. All green.",
    "priority": "normal",
    "voice": "am_michael",
    "timestamp": "2026-03-06T16:00:00.000Z",
    "thread_id": "abc-123-def-456",
    "file_url": "http://100.122.241.82:8600/files/abc123/report.pdf",
    "file_name": "report.pdf",
    "file_size": "12345",
    "file_urls": "url1,url2",
    "file_names": "file1.pdf,file2.csv",
    "file_sizes": "12345,67890"
  }
}
```

The `voice` field is a Kokoro voice ID. If absent, the app maps sender name to voice. File fields are optional. Multi-file fields are comma-separated (v2.6+). `thread_id` links to cmail conversation threads (v3.0+).

## Audio Pipeline

**Primary: GPU TTS (Oasis)**
- POSTs `{text, voice, speed}` to Kokoro TTS at `http://100.80.140.52:8400/api/tts`
- Server returns complete WAV file (16-bit PCM, 24kHz, mono)
- App strips 44-byte WAV header, plays raw PCM via AudioTrack
- AudioTrack: MODE_STREAM, PERFORMANCE_MODE_LOW_LATENCY
- Retry once after 2s delay for Tailscale tunnel cold-start
- `Connection: close` header required (socat proxy compatibility)
- Connect timeout: 5s, Read timeout: 30s
- PARTIAL_WAKE_LOCK held during playback (2 min safety timeout)

**Fallback: On-device Piper TTS**
- Model: Piper libritts_r-medium via sherpa-onnx (904 speakers, 22050Hz)
- `speakBlocking()` on playback executor -- no concurrent AudioTracks
- System TextToSpeech as last-resort fallback

**Thread Architecture:**
- Single-thread download executor (preserves FIFO message ordering)
- Single-thread playback executor (prevents AudioTrack overlap)
- `onComplete` sentinel queued on playback executor after each message
- Ensures proper service teardown only after last message plays

## Background Reliability

FCM data messages get ~10 seconds of execution time. Audio playback takes longer.

Solution: `AudioPlaybackService` (foreground service with `mediaPlayback` type).

1. FCM handler starts `AudioPlaybackService` via `startForegroundService()`
2. Service promotes to foreground immediately (visible notification)
3. `AtomicInteger` pending count tracks queued messages
4. Runs full audio pipeline (download + playback) without time pressure
5. Multi-message: each FCM increments count, each completion decrements
6. Service stops itself only when pending count reaches 0
7. Notification shows remaining count during multi-message playback

This survives indefinite background time, Doze mode, and battery optimization.

## App Screens

### Messages Tab
- Incoming messages with sender, timestamp, body
- Tap to copy text, long-press to copy with sender name
- Replay button re-speaks via GPU TTS with original voice
- File download buttons (multi-file support, progress tracking)
- Outgoing messages shown with "You -> department" header (distinct styling)
- Watch button on invoked messages opens live session monitor

### Send Tab
- Searchable multi-select department picker (name, description, voice)
- Select All / Clear All for broadcast
- Message compose field
- File attachment picker (multiple files)
- Sends cmail with agent invocation to each selected department

### Activity Tab (3 segments)

**Sessions**: Agent session list from session pipeline database
- Department badges, model name, record count, git branch
- Context window usage bar (green/orange/red by percentage)
- Compact button for completed sessions
- Tap to view full session detail (user/assistant/tool records)

**Threads**: Cmail conversation threads from File Bridge
- Thread cards with subject, participants, message count, preview
- Thread detail with chronological message bubbles
- Reply compose with optimistic UI (instant bubble, rollback on failure)
- FCM-driven auto-refresh (no polling)
- Safety net: 30s/60s fallback checks if FCM delayed

**Events**: Flight recorder telemetry feed
- Color-coded by type: tool_used (green), tool_failed (red), session_ended (orange), session_compacting (blue), agent_idle (purple)
- SSE-driven auto-refresh when foregrounded
- Monospace summary text for at-a-glance readability

### Settings Tab
- FCM token display (selectable for copy)
- Voice assignments: department -> Kokoro voice mapping (tap to change, preview button)
- Voice model status (Ready/Extracting/Error)
- Piper voice settings: speaker ID (0-49), speed slider (0.5x-2.0x), test button
- GPU stream diagnostics: Test Connect + Test Stream

### Log Viewer (bug icon overlay)
- In-memory Timber log viewer with level filtering (ALL/A/E/W/I/D)
- Tag filter with multi-select picker
- Crash detection banner (loads previous session logs)
- Copy all / clear buttons
- Auto-scrolls to newest entries

### Live Session Screen (Watch button overlay)
- Two-phase: discovery (finds session by department + time window) then watching
- Discovery: polls active sessions, falls back to recent completed, 2-min timeout
- Watching: incremental record fetch every 2.5s via `since_sequence`
- Auto-scrolls as new records arrive
- Status indicator: "Live" (green) or "Completed"
- Transient poll failures show banner without aborting

## Real-Time Architecture

The app uses three complementary real-time channels:

**FCM (background + foreground):**
- Message delivery and audio playback
- Thread refresh signals (via `thread_id` in payload)
- Works when app is backgrounded or killed

**SSE (foreground only):**
- `EventStreamClient`: lifecycle-aware, connects on foreground, disconnects on background
- Handles: cmail thread replies (fills the FCM gap), flight recorder events, session events
- Auto-reconnects with `Last-Event-ID` for seamless recovery
- Increments `eventFeedRefresh` StateFlow for UI triggers

**Safety Net Polling (fallback only):**
- Thread replies: 2 checks at 30s and 60s, only if FCM push doesn't arrive
- Session watching: 2.5s interval poll (indexed SQLite query, effectively free)

## Data Persistence

**Room Database (on device):**
- `dispatch.db` with `messages` table (MessageEntity)
- Messages survive crashes, process death, restarts
- Auto-trim to 100 messages on insert
- Destructive migration (this is a cache -- pop-os dispatch history DB is source of truth)
- Fire-and-forget async writes via internal CoroutineScope

**MessageRepository pattern:**
- StateFlow is the reactive UI source (fast, main-safe)
- Room is the durable backing store (async IO dispatcher)
- On init: Room contents load into StateFlow
- `addMessage()`: updates StateFlow immediately, persists to Room async
- `threadRefreshEvents` SharedFlow for cross-screen thread updates

## Bilateral Communication

The app sends messages to DG departments through File Bridge (pop-os:8600), which relays them through cmail.

**Flow:** App -> File Bridge `/cmail/send` -> `cmail send <dept> <msg> -i` -> department inbox + agent invocation

**Components:**
- `FileTransferClient.sendCmail()` -- POSTs JSON to File Bridge (supports thread_id for reply-in-thread)
- `FileTransferClient.fetchDepartments()` -- Gets department list for UI picker
- `FileTransferClient.replyToThread()` -- Thread-aware reply (wraps sendCmail with thread_id)
- `CmailSendResult` returns: success, invoked flag, department, sessionId

Sender identity is "nigel" (determined by File Bridge cwd).

## Voice Mapping

Each sender gets a distinct Kokoro voice. Centralized on File Bridge with app-side fallback:

```
engineering    -> am_michael
watchman       -> am_adam
boardroom/ceo  -> am_eric
dispatch       -> am_puck
aegis          -> am_fenrir
research       -> bm_george
hunter         -> am_onyx
alchemist      -> am_liam
prompt-engine  -> am_echo
trinity        -> af_nova
it             -> am_adam
```

Voice assignments editable from Settings tab (fetches/updates via File Bridge `/config/voice-map`).

## Project Structure

```
Dispatch/
  app/src/main/
    AndroidManifest.xml
    res/xml/network_security_config.xml
    assets/
      kokoro/                          # Kokoro espeak-ng data + lexicon
      piper/                           # Piper ONNX model + tokens + espeak data
    java/dev/digitalgnosis/dispatch/
      DispatchApplication.kt           # @HiltAndroidApp, Timber, FCM token, ModelManager, SSE start
      config/
        TailscaleConfig.kt             # Oasis + DG Core + File Bridge IPs
        TokenManager.kt                # FCM token: SharedPreferences + StateFlow + file
      data/
        DispatchMessage.kt             # Message data class (files, thread, invoke fields)
        MessageEntity.kt               # Room entity with domain model converters
        MessageDao.kt                  # Room DAO (insert, getAll, trim, delete)
        DispatchDatabase.kt            # Room database definition (v1, destructive migration)
        MessageRepository.kt           # StateFlow + Room persistence + thread refresh events
        ThreadModels.kt                # ThreadInfo, ThreadDetail, ThreadMessage
        SessionModels.kt               # SessionInfo, SessionDetail, SessionRecord, SessionDebugInfo
        EventModels.kt                 # OrchestratorEvent
      di/
        AppModule.kt                   # Hilt: provides DispatchDatabase + MessageDao
      fcm/
        DispatchFcmService.kt          # FCM handler -> message persistence + foreground service
        AudioPlaybackService.kt        # Foreground service: pending count, audio queue, teardown
        FcmEntryPoint.kt               # Hilt @EntryPoint for service DI (not AndroidEntryPoint)
      logging/
        BigNickTimberTree.kt           # Error reporting to Route 33
        FileLogTree.kt                 # File-based logging (SSH-accessible, daily rotation)
        InMemoryLogTree.kt             # In-memory log buffer for UI viewer
      network/
        AudioStreamClient.kt           # GPU TTS: download WAV, retry, play PCM via AudioTrack
        FileTransferClient.kt          # File Bridge: files, cmail, threads, sessions, events, voice map
        EventStreamClient.kt           # SSE client: lifecycle-aware, thread/event refresh signals
      tts/
        TtsEngine.kt                   # Piper VITS fallback + system TTS last resort
        ModelManager.kt                # Asset extraction, state machine (NotReady/Extracting/Ready/Error)
      ui/
        MainActivity.kt                # Compose host: tab nav + modal overlays (~195 lines)
        LogViewerScreen.kt             # On-device log viewer with filtering + crash detection
        navigation/
          DispatchTab.kt               # Bottom nav: Messages, Send, Activity, Settings
        screens/
          MessagesScreen.kt            # Message list with replay, download, watch
          SendScreen.kt                # Department picker, compose, file attach, broadcast
          SettingsScreen.kt            # Voice config, diagnostics, token, model status
          ThreadsScreen.kt             # Thread list + detail + reply + optimistic UI
          ActivityScreen.kt            # Sessions/Threads/Events segmented toggle
          EventFeedScreen.kt           # Flight recorder event feed
          LiveSessionScreen.kt         # Real-time session monitoring (discovery + watching)
        components/
          DepartmentPicker.kt          # Searchable multi-select department list
        theme/                         # Material3 dark theme (Color, Theme, Type)
  app/libs/
    sherpa-onnx-1.12.28.aar           # On-device TTS native library (40MB)
  app/build.gradle.kts
  gradle/libs.versions.toml
  settings.gradle.kts
```

## Tech Stack

- **Language:** Kotlin 2.0.21
- **UI:** Jetpack Compose + Material3 (BOM 2026.01.01)
- **DI:** Hilt 2.51.1 (via KSP 2.0.21-1.0.25)
- **Messaging:** Firebase Cloud Messaging (BOM 33.7.0)
- **Database:** Room 2.6.1 (local message persistence)
- **GPU TTS:** Kokoro v1.0 via HTTP (Oasis Docker container)
- **Fallback TTS:** Piper VITS via sherpa-onnx 1.12.28
- **Networking:** OkHttp 4.12.0 (SSE), HttpURLConnection (TTS + File Bridge)
- **Real-time:** OkHttp SSE 4.12.0 (event stream), FCM (push)
- **Lifecycle:** androidx.lifecycle-process 2.8.7 (foreground/background detection)
- **Logging:** Timber 5.0.1 (memory + file + remote)
- **Build:** AGP 8.9.1, Gradle 8.11.1, Java 11
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34

## Hilt DI Architecture

- `DispatchApplication` is `@HiltAndroidApp` entry point
- `MainActivity` is `@AndroidEntryPoint` -- field injection works
- `DispatchFcmService` and `AudioPlaybackService` are NOT AndroidEntryPoints -- they use `@EntryPoint` interface (`FcmEntryPoint`) with `EntryPointAccessors.fromApplication()`
- `AppModule` provides Room database + DAO
- All services are `@Singleton` + `@Inject constructor`

## Setup

### Firebase
1. Firebase project with Cloud Messaging enabled
2. `google-services.json` in `app/` (gitignored)
3. Package name: `dev.digitalgnosis.dispatch`

### Build & Deploy
```bash
./gradlew assembleDebug
scp app/build/outputs/apk/debug/app-debug.apk pixel9:~/storage/downloads/dispatch.apk
```

### First Launch
1. Grant notification permission (Android 13+)
2. FCM token appears in Settings tab -- copy to CLI config
3. Use "Test Connect" in Settings to verify Oasis GPU reachability
4. Use "Test Stream" to verify end-to-end GPU audio
5. Try sending a message to a department from the Send tab
6. Check Activity tab for sessions and threads

## Network

Cleartext HTTP allowed globally (`network_security_config.xml`) since all traffic routes over Tailscale VPN.

- `100.80.140.52:8400` -- Oasis TTS server (Kokoro Docker via socat proxy)
- `100.83.30.70:8085` -- DG Core (Route 33 error reporting)
- `100.122.241.82:8600` -- File Bridge (files, cmail, threads, sessions, events, voice map, dispatch history)

## File Bridge API Surface

The app consumes these File Bridge endpoints:

**Cmail:**
- `POST /cmail/send` -- Send message (with optional thread_id, invoke flag)
- `GET /cmail/departments` -- Department list for picker
- `GET /cmail/threads` -- Thread list (with filters)
- `GET /cmail/threads/{id}` -- Thread detail (merged cmail + dispatch messages)

**Sessions:**
- `GET /sessions` -- Session list with filters (dept, status, limit)
- `GET /sessions/active` -- Active sessions only
- `GET /sessions/{id}` -- Session detail with incremental records (since_sequence)
- `POST /sessions/{id}/command` -- Session commands (compact)

**Events:**
- `GET /feed` -- Event feed (event_type, department filters)
- `GET /events/stream` -- SSE endpoint (real-time push, Last-Event-ID support)

**Files:**
- `POST /upload` -- Upload file to department inbox
- `GET /files/{id}/{name}` -- Download staged file

**Config:**
- `GET /config/voice-map` -- Department-to-voice mapping
- `PUT /config/voice-map` -- Update voice assignment

**History:**
- `GET /dispatch/history` -- Paginated message history
- `GET /dispatch/stats` -- Message statistics

## On-Device Logs

App writes Timber logs to shared storage via FileLogTree. Accessible over SSH:
```bash
ssh pixel9 "cat ~/storage/shared/Download/dispatch-logs/dispatch-$(date +%Y-%m-%d).log"
ssh pixel9 "tail -50 ~/storage/shared/Download/dispatch-logs/dispatch-$(date +%Y-%m-%d).log"
```

Location: `/storage/emulated/0/Download/dispatch-logs/dispatch-YYYY-MM-DD.log`
- Rotates daily by date
- Contains FCM receive times, audio pipeline events, HTTP timing, service lifecycle
- Survives app restarts (written to shared storage, not app-private)
- Also viewable in-app via bug icon -> LogViewerScreen
- Crash detection: FileLogTree detects unclean shutdown, surfaces previous session logs

## Companion Projects

- **CLI:** `digital-gnosis/tools/dispatch/` -- Sends FCM messages, logs to history DB
- **File Bridge:** `digital-gnosis/tools/file-bridge/` -- Mobile gateway to cmail, sessions, events
- **Oasis TTS:** `oasis:/home/nigelw/dispatch-audio/` -- Docker Kokoro TTS + socat proxy
- **Session Pipeline:** `digital-gnosis/tools/dg-it/` -- JSONL watcher daemon -> sessions.db
- **Flight Recorder:** `digital-gnosis/tools/hooks/flight-recorder-hook.py` -- Agent telemetry

## Security

- `google-services.json` is gitignored
- Firebase service account keys never committed
- Debug keystore only (no release signing)
- FCM uses data-only messages (no server-side notification rendering)
- All TTS traffic over Tailscale (never public internet)
- Cmail relay validates message content server-side
- SSE endpoint is Tailscale-internal only
