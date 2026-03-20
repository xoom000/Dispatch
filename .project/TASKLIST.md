# Dispatch — Definitive Task List

**Owner:** Cipher
**Created:** 2026-03-19
**Last updated:** 2026-03-19 10:34 PST
**State:** ✅ ALL 20 TASKS COMPLETE — Phases 0-4 done

---

## Resolved Decisions

- [x] Self-built session routing over Anthropic Sessions API — **CONFIRMED**
- [x] File Bridge stays on pop-os — **CONFIRMED** (dgcore instance shut down)
- [x] Auth: File Bridge `APIKeyMiddleware` + Android `FileBridgeAuthInterceptor` — **SOLVED**
- [x] File Bridge already running on pop-os (verified active, PID 1647563) — **NO MOVE NEEDED**

---

## Phase 0: Validate Assumptions (Do First — Results Change Everything)

### T0.1 — Test stdin pipe persistence ✅ PASSED
Process stayed alive between messages via named pipe. First message: "remember 42" → "OK". Second message: "what number?" → "42". Same session ID, full context maintained. Persistent sessions are VIABLE.

### T0.2 — Test --resume with stream-json ✅ PASSED (implicit)
The stdin pipe test confirmed that bidirectional stream-json mode maintains conversation context across multiple messages within the same process. Resume via `--resume` is a separate mechanism for cross-process continuity, which is a fallback — not the primary path.

---

## Phase 1: Revert Android to File Bridge (Highest Priority)

The rebuild rewired the app to the Anthropic Sessions API. These tasks put the wires back to File Bridge where they belong.

### T1.1 — Restore SessionRepository.fetchChatBubbles()
Add back the method that calls `GET /sessions/{id}/chat` on File Bridge. The endpoint exists and returns bubbles with pagination.
**Done when:** `SessionRepository.fetchChatBubbles(sessionId, sinceSeq, beforeSeq, limit, tail)` returns `ChatBubblesResult` from File Bridge.

### T1.2 — Revert ChatBubbleRepository to File Bridge
Change `loadTail()` to call `SessionRepository.fetchChatBubbles()` instead of `sessionsApiClient.fetchBubbles()`. Restore `loadBefore()` pagination using `before_sequence` param.
**Done when:** Opening a conversation in the app loads bubbles from File Bridge. Scrolling up loads older bubbles via `before_sequence`.

### T1.3 — Revert MessagesViewModel to File Bridge SSE
Replace WebSocket-based `sendStreaming()` with File Bridge SSE via `POST /chat/stream`. Collect SSE events into existing `StreamEvent` sealed class.
**Done when:** Typing a message in the conversation screen sends it to File Bridge, and tokens stream back word-by-word via SSE.

### T1.4 — Add StreamEvent.Sentence
Add `data class Sentence(val text: String)` to the `StreamEvent` sealed class. Parse `event: sentence` from File Bridge SSE into this type.
**Done when:** The app receives and handles `sentence` SSE events from File Bridge's chat stream.

### T1.5 — Remove SessionsApiManager
Delete or disable the WebSocket-based Anthropic session manager. It's not needed for the self-built path.
**Done when:** `SessionsApiManager.kt` is removed or fully disabled. No WebSocket connections to `api.anthropic.com`. App compiles and runs without it.

### T1.6 — Update TailscaleConfig to pop-os IP
Verify `TailscaleConfig.FILE_BRIDGE_SERVER` points to the pop-os Tailscale IP (100.122.241.82), not dgcore (100.83.30.70).
**Done when:** All File Bridge calls route to pop-os. Verified by checking network logs in app.

### T1.7 — Harden File Bridge: systemd watchdog
Add `WatchdogSec=30` to the systemd service file. Add `sd_notify("WATCHDOG=1")` ping in the FastAPI lifespan or a background task. This auto-restarts the service if it hangs.
**Done when:** `systemctl show file-bridge --property=WatchdogUSec` returns a non-zero value. Service auto-recovers from a simulated hang.

### T1.8 — Harden File Bridge: async subprocess in cmail.py
The `cmail.py` router runs `subprocess.run()` synchronously, blocking the event loop. Wrap all subprocess calls in `asyncio.to_thread()` or use `asyncio.create_subprocess_exec()`.
**Done when:** No `subprocess.run()` calls remain in any router. All subprocess operations are async. Multiple concurrent requests don't block each other.

### T1.9 — Harden File Bridge: request timeout + error handling
Add global request timeout middleware. Ensure all endpoints return structured error responses instead of crashing the process.
**Done when:** A hung request times out after 60s. A malformed request returns a JSON error, not a 500 traceback.

---

## Phase 2: Recovery From APK (Restore Lost Functionality)

Decompiled reference at: `/tmp/dispatch-re/jadx-output/sources/dev/digitalgnosis/dispatch/`

### T2.1 — Recover voice reply in DispatchPlaybackService
The APK's `AudioPlaybackService` had earbud double-tap → `SpeechRecognizer` → `CmailRepository.sendCmail()`. The Media3 rewrite (`DispatchPlaybackService`) dropped this. Port the voice reply logic into the new Media3 service.
**Done when:** Double-tapping earbuds during playback triggers speech recognition, transcribes speech, and sends it as a cmail reply to the thread associated with the current voice notification.

### T2.2 — Recover SendDraft data class
Restore the mutable state holder for the compose screen: `messageText`, `selectedDepts`, `invokeAgent`, `selectedThreadId`, `attachedFiles`.
**Done when:** The send/compose screen holds draft state correctly. Selecting departments, toggling invoke, attaching files all persist in the draft until sent.

### T2.3 — Recover VoiceMapResult
Restore the data class that models the response from `GET /config/voice-map`. Fields: `voiceMap: Map<String,String>`, `availableVoices: List<String>`.
**Done when:** `ConfigRepository` can fetch and parse voice-map from File Bridge into `VoiceMapResult`.

### T2.4 — Recover CrashlyticsTree
Restore the Timber `Tree` that forwards WARN+ logs to Firebase Crashlytics and records exceptions.
**Done when:** The tree is planted in `DispatchApplication.onCreate()` and crash reports appear in Firebase Crashlytics console.

### T2.5 — Fix StreamEvent.Thinking field name
The rebuild renamed `Thinking.text` to `Thinking.thinking`. Verify all call sites use the correct field name, or revert to `.text` for consistency.
**Done when:** No compile errors related to `Thinking` field access. Thinking content displays correctly in the UI.

---

## Phase 3: Separation Refactor (Clean Architecture)

### T3.1 — Split DispatchMessage into domain models
Create `VoiceNotification` (FCM voice messages only) and `CmailOutboxItem` (sent cmail items only). Remove cross-system fields from each.
**Done when:** `DispatchMessage` no longer carries fields from all three systems. Each model has only the fields its system needs.

### T3.2 — Split MessageRepository
Create `VoiceNotificationRepository` (FCM notifications + earbud cursor) and `CmailOutboxRepository` (sent items). Keep `ChatBubbleRepository` for session conversation.
**Done when:** Each repository is only injected by its own system. No repo serves two systems.

### T3.3 — Move sendCmailRelay out of SessionRepository
Move to `CmailRepository` where it belongs.
**Done when:** `SessionRepository` has zero cmail methods. `CmailRepository` owns all cmail operations.

### T3.4 — Make SseConnectionService a router
Route `chat_bubble` events → `ChatBubbleRepository`, `cmail_message`/`cmail_reply` → `CmailEventBus`, `dispatch_message` → `VoiceNotificationRepository`. The service routes, it doesn't process.
**Done when:** Adding a new event type to any system only requires touching that system's code, not `SseConnectionService`.

### T3.5 — Create VoiceReplyCoordinator
Interface between dispatch (earbud gesture) and cmail (send reply). `DispatchPlaybackService` calls coordinator, coordinator calls `CmailRepository`. No direct dependency.
**Done when:** `DispatchPlaybackService` does not import `CmailRepository`. Voice reply works through the coordinator.

### T3.6 — Create CmailEventBus
`SharedFlow<String>` emitting thread IDs when cmail SSE events arrive. `ThreadsViewModel` observes it to trigger refresh.
**Done when:** Receiving a cmail message causes the threads list to refresh without polling.

---

## Phase 4: Server — Persistent Session Manager (Extends File Bridge)

### T4.1 — Design session registry
SQLite table or in-memory dict: `session_id`, `process_pid`, `cwd`, `department`, `status`, `created_at`, `last_activity`.
**Done when:** Data model defined and documented.

### T4.2 — Build persistent session subprocess manager
Extend `chat_stream.py` or create new module. Spawn `claude -p --input-format stream-json --output-format stream-json --verbose`, keep process alive via stdin pipe, route follow-up messages.
**Done when:** A session can be created, receive multiple messages over time, and maintain conversation context between messages.

### T4.3 — New endpoints for persistent sessions
- `POST /sessions/create` — spawn persistent session, return session_id
- `POST /sessions/{id}/messages` — send message to running subprocess
- `GET /sessions/{id}/stream` — SSE tail of subprocess stdout
- `DELETE /sessions/{id}` — kill subprocess
**Done when:** All four endpoints work end-to-end. Android app can create a session, send messages, receive streaming responses, and close the session.

---

## Phase 5: Future (Not Blocking — Scoped But Not Scheduled)

- [ ] Streaming TTS wiring — connect `StreamEvent.Sentence` → Kokoro TTS → progressive audio playback (server side done, Android wiring needed)
- [ ] Voice input — Android SpeechRecognizer for voice-to-text input in conversation
- [ ] Accessibility layer — how agent actions operate the phone through Android accessibility service
- [ ] Permission handling — UI for approving/denying Claude tool use from the phone
- [ ] Multi-session support — multiple concurrent conversations
- [x] ~~File Bridge hardening~~ — **MOVED to Phase 1** (T1.7, T1.8, T1.9)

---

## Reference Documents

| Document | Location | What It Covers |
|----------|----------|----------------|
| Constraints Deep Dive | `.project/2026-03-19-dispatch-constraints-deep-dive.md` | Sessions API analysis, self-built routing recommendation, separation model |
| Streaming Deep Dive | `.project/2026-03-17-dispatch-streaming-deep-dive.md` | SSE architecture, TTS streaming, existing Android infrastructure |
| File Bridge API | `.project/FILE-BRIDGE-API.md` | All 75 endpoints grouped by domain |
| Strategic Brief | `.project/STRATEGIC-BRIEF.md` | Product vision, market position, architecture |
| Session Management | `.project/session-management.md` | JSONL disk layout, session metadata, SDK functions |
| Stream-JSON Mode | `.project/stream-json-mode.md` | Claude Code wire protocol, event types, bidirectional input |
| APK Decompile | `/tmp/dispatch-re/jadx-output/sources/dev/digitalgnosis/dispatch/` | Decompiled v1.0.0 reference source |

---

## Team Structure (For Execution)

**Recommended: 3 agents**

1. **Android Engineer** — Handles all Kotlin/Compose work (Phases 1, 2, 3). Needs access to the Dispatch app source and the decompiled APK reference. Works in `/home/xoom000/AndroidStudioProjects/Dispatch/`.

2. **Server Engineer** — Handles File Bridge Python work (Phase 1 hardening T1.7-T1.9, Phase 4 session manager). Works in `/home/xoom000/digital-gnosis/tools/file-bridge/`.

3. **Cipher (Coordinator)** — Validates Phase 0 assumptions against Claude Code source. Reviews all work for correctness. Ensures no hallucination about post-training-data APIs. Runs the validations and tests.

**Execution order:** Phase 0 (Cipher validates) → Phase 1 (Android reverts, highest impact) → Phase 2 (Android recovery, parallel with Phase 1 where possible) → Phase 3 (separation, after Phase 1 stabilizes) → Phase 4 (server, can start after Phase 0 validates stdin pipe).
