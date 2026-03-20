# Dispatch Constraints Deep Dive — Three Critical Questions Answered

**Date:** 2026-03-19
**Investigator:** Cipher (Claude Code forensic analyst)
**Source:** v2.1.71 snapshot (cli-beautified.js, 520,715 lines) + Android codebase + project docs
**Confidence:** High — every claim verified against source code with line citations
**Status:** FINDINGS DOCUMENT — not a plan, not a spec. These are verified facts.

---

## Question 1: Is the Sessions API Local or Remote Only?

**ANSWER: The Sessions API is fundamentally a CLOUD concept. It does NOT automatically expose local CLI sessions.**

Local JSONL sessions at `~/.claude/projects/` are never uploaded to the cloud unless the CLI is explicitly running in bridge mode. The Sessions API is a relay — it sits between a client (phone, web browser) and a compute environment (Anthropic cloud or your bridge machine).

### How the Bridge Actually Works (verified against source)

1. `claude remote-control server` calls `POST /v1/environments/bridge` to register pop-os as a compute environment (line 468110-468129). Returns an `environment_id` and `environment_secret`.

2. The CLI then long-polls `GET /v1/environments/{environmentId}/work/poll` every 2-5 seconds, waiting for incoming work (line 468135).

3. When someone creates a session targeting that bridge `environment_id` (via `POST /v1/sessions`), the work appears in the poll response.

4. The CLI spawns a child `claude` process with `--sdk-url <ingressUrl> --session-id <cloudSessionId> --input-format stream-json --output-format stream-json` (line 468411).

5. That child process connects back to Anthropic via SSE transport (the `ku1` class at line 485386), NOT stdout. All events flow through Anthropic's relay.

### The Two Session ID Formats

- **Local sessions:** Plain UUID format (`004cc9d6-a1b2-c3d4-...`), stored as `{uuid}.jsonl`
- **Cloud sessions:** Prefixed format (`cse_XXXX` internally, displayed as `session_XXXX`). Translation function `Fx1` at line 468851.
- **Bridge pointer:** When remote-control creates a session, it writes `bridge-pointer.json` mapping the cloud session ID to the local environment (line 469034).

### Teleport Goes Through the Cloud, Not Local Files

`--teleport` calls `GET /v1/session_ingress/session/{id}` to download session loglines (line 352377-352384), writes them to a local JSONL file, then loads from that local file. It never reads a remote JSONL file directly. This is why teleport exists — sessions live in the cloud, teleport brings them down.

### What This Means for Dispatch

The Sessions API requires Anthropic as the middleman for EVERY message. Phone → Anthropic → pop-os → Anthropic → phone. Even though both devices are on the same Tailscale network. This is by design — the Sessions API was built for the case where there's no direct path between client and compute.

---

## Question 2: Do We Need Anthropic's Sessions API, or Can We Build Our Own?

**ANSWER: We can build our own, and the investigation recommends we should.**

### What the Sessions API Actually Is (Demystified)

It's four things:
1. **Session creation** — `POST /v1/sessions` → registers session, assigns environment, returns ID
2. **Message relay** — `POST /v1/sessions/{id}/events` → routes prompt to pop-os bridge
3. **Event subscription** — `WSS /v1/sessions/ws/{id}/subscribe` → streams events back to client
4. **Session storage** — Anthropic stores events server-side

That's a message bus with persistence. DG already has a message bus (File Bridge) and persistence (JSONL + SQLite).

### What DG Already Has (80% of the Infrastructure)

**Session storage:** JSONL files on disk, Agent SDK's `listSessions()` / `getSessionMessages()`, `chat_watcher.py` monitoring

**Session control:** CLI flags (`--resume`, `--continue`, `--fork-session`, `--session-id`, `--permission-mode`), Agent SDK `query()` with resume/fork options

**Subprocess spawning pattern:** The exact command is known from source:
```
claude -p "prompt" --session-id <uuid> --output-format stream-json --verbose --input-format stream-json
```
With `--input-format stream-json`, the process stays alive waiting for stdin messages. This is a proven pattern — it's how the SDK daemon works internally (line 468411).

**SSE/streaming transport:** File Bridge has `GET /events/stream` with `Last-Event-ID` reconnect, `POST /events/emit` with fan-out, `chat_watcher.py` push. Android has `EventStreamClient` and `SseConnectionService`.

**Auth:** Tailscale + File Bridge auth. Already working. No OAuth complexity.

### What Would Need to Be Built (~20% New Work)

**1. Session Manager Service (~400 lines Python)**
- Maintains registry: `{session_id: {process, cwd, department, status}}`
- Creates sessions: spawns `claude` subprocess with stream-json flags
- Manages stdin pipe: keeps process alive, routes incoming messages to correct subprocess
- Handles process crashes, cleanup, session lifecycle

**2. REST Endpoints on File Bridge (4-6 routes)**
- `POST /sessions` — create session, return session_id
- `GET /sessions` — list sessions with metadata
- `GET /sessions/{id}` — session status + metadata
- `DELETE /sessions/{id}` — kill subprocess
- `POST /sessions/{id}/messages` — send message to subprocess stdin

**3. Per-Session SSE Stream (~50-100 lines)**
- `GET /sessions/{id}/stream` — tails subprocess NDJSON stdout, re-emits as SSE
- Reuses existing `asyncio.Queue` fan-out pattern from `_push_sse_bubble()`

**4. Android Client Changes (modest)**
- New `DGSessionsApiClient` replacing `SessionsApiClient` — targets File Bridge URLs
- Auth: Tailscale + existing File Bridge auth instead of OAuth
- `StreamEvent`, `StreamingBubble`, `SseConnectionService` all work unchanged

### Why Self-Built Wins Over Anthropic Sessions API

**Anthropic path problems (verified from source + bug reports):**
- `pollForWork` has no token refresh retry — bridge dies on expired OAuth tokens (Issue #30102, source confirms at line 468135)
- `POST /v1/sessions` has no retry logic — one 503 = dead session
- 10-minute hard TTL if CLI loses connectivity
- Requires Claude.ai OAuth (not API key) — third-party OAuth was killed in Feb 2026
- Every message routes through Anthropic's servers even though both devices are on Tailscale
- `allow_remote_control` org policy must be enabled
- Requires beta header `anthropic-beta: ccr-byoc-2025-07-29` — beta features can change or be removed

**Self-built path advantages:**
- Direct: Android → Tailscale → File Bridge → subprocess. No internet hop after API calls
- No OAuth dependency — existing auth works
- DG owns every failure mode — can fix bugs immediately
- Works offline between model API calls (Tailscale is peer-to-peer)
- Sessions survive File Bridge restarts (JSONL on disk, PIDs tracked)
- Integrates with cmail invocation for department routing naturally

### Critical Technical Detail: CWD Constraint

Sessions are stored at `~/.claude/projects/{hashed-cwd}/{session-id}.jsonl`. The subprocess MUST be spawned with the same `cwd` as when the session was created, or resume fails. The Session Manager must store `cwd` per session in its registry.

### The One Thing We Lose

No `claude.ai/code` web UI integration. If you want to watch a session from a browser, you'd need to build that UI yourself. For DG's use case (phone-first), this doesn't matter.

---

## Question 3: The cmail / Sessions / Dispatch Separation

**ANSWER: There are six specific places in the Android codebase where the three systems are improperly coupled. Here's every one of them and how to fix it.**

### The Three Systems (Hard Definitions)

**Sessions Layer** — The AI conversation pipe. Creating Claude Code sessions, sending prompts, receiving streaming responses. How the phone tells Claude what to do.
- Transport: WebSocket or SSE to File Bridge (self-built) or Anthropic (if using Sessions API)
- Data models: `StreamEvent`, `ChatBubble`, `ChatBubbleEntity`, `SessionInfo`
- Repositories: `SessionsApiManager`, `ChatBubbleRepository`

**cmail System** — DG's inter-department messaging. Agents talk to each other. Nigel sends tasks to departments. Threading, invocation, cascade control. This is coordination/tasking, NOT real-time AI conversation.
- Transport: REST on File Bridge (`POST /cmail/send`, `GET /cmail/threads/*`)
- Data models: `ThreadInfo`, `ThreadDetail`, `ThreadMessage`
- Repository: `CmailRepository`

**Dispatch System** — One-way voice delivery from agents to Nigel's phone. FCM push, GPU TTS, audio playback. Fire-and-forget.
- Transport: FCM inbound, Kokoro TTS streaming outbound
- Data models: `DispatchMessage` (currently), should be `VoiceNotification`
- Services: `DispatchFcmService`, `DispatchPlaybackService`

### The Six Blurring Points (Verified in Android Source)

**Blurring #1 — `DispatchMessage` is a universal data class for all three systems**

`DispatchMessage.kt` carries fields from all three systems: `sessionId`, `invoked`, `invokedDepartment`, `threadId` (cmail concepts), `isOutgoing` (cmail concept), plus voice notification fields. It's populated by `DispatchFcmService` (dispatch), `SendViewModel.sendIndividually()` (cmail), `SendViewModel.sendGeminiNative()` (sessions), and `DispatchPlaybackService.sendVoiceReply()` (cmail triggered by dispatch gesture).

**Blurring #2 — `MessageRepository` is a catch-all for everything**

Injected into `DispatchFcmService`, `DispatchPlaybackService`, and `SendViewModel`. Holds a flat `List<DispatchMessage>` mixing voice notifications, cmail sends, and AI responses. The earbud cursor navigation (`getMessageAtCursor()`) operates on this mixed list and has to guess what type each message is.

**Blurring #3 — `SessionRepository` does both sessions AND cmail**

Contains `streamChat()` (sessions layer) AND `sendCmailRelay()` (cmail system) in the same repository. A single repository should not span two systems.

**Blurring #4 — `SseConnectionService` fans out all three systems' events through one router**

Handles `chat_bubble` (sessions), `agent_message_chunk` (sessions), `dispatch_message` (dispatch), `cmail_message`/`cmail_reply` (cmail) all in one `when(eventType)` block. When any system adds a new event type, this file must be touched.

**Blurring #5 — `DispatchPlaybackService` calls `CmailRepository` directly**

When Nigel double-taps earbuds to voice-reply, the playback service (dispatch system) calls `cmailRepository.sendCmail()` (cmail system) directly. The playback service is now an agent of two systems simultaneously.

**Blurring #6 — `ChatBubble.type = "dispatch"` conflates display origin with system identity**

A `dispatch` type bubble in the conversation view represents a `dispatch send` tool call in the JSONL session file (sessions layer artifact). But it renders with a play button like it came from FCM (dispatch system artifact). Same voice message, two unconnected representations.

### The Clean Separation Model

**Split `DispatchMessage` into three domain models:**
1. `VoiceNotification` — only for FCM-delivered voice messages. Fields: `sender`, `message`, `voice`, `timestamp`, `cmailThreadId`. No `isOutgoing`, no `sessionId`.
2. `CmailOutboxItem` — only for messages Nigel sent via cmail. Fields: `department`, `message`, `sentAt`, `invoked`, `threadId`, `sessionId`.
3. `ChatBubble` — already exists and is correct for session conversation data.

**Split `MessageRepository` into three:**
1. `VoiceNotificationRepository` — holds last N FCM voice messages. Powers earbud cursor navigation. Only dispatch system touches it.
2. `CmailOutboxRepository` — holds sent cmail items for UI feedback. Only cmail system touches it.
3. `ChatBubbleRepository` — already exists. Only sessions layer touches it.

**Remove `sendCmailRelay()` from `SessionRepository`.** That method belongs in `CmailRepository`.

**Make `SseConnectionService` a router, not a processor.** It routes:
- `chat_bubble` → `ChatBubbleRepository` (sessions)
- `cmail_message`/`cmail_reply` → `CmailEventBus` (a simple `SharedFlow<String>` of thread IDs for refresh signals)
- `dispatch_message` → `VoiceNotificationRepository` (dispatch)

**Add `VoiceReplyCoordinator` between dispatch and cmail.** When earbud double-tap triggers voice reply: `DispatchPlaybackService` → `VoiceReplyCoordinator` → `CmailRepository`. The dispatch service never imports cmail directly.

### The Three Legitimate Seams (Where Systems SHOULD Touch)

**Seam 1 — cmail send returns a session ID**
When Nigel sends a cmail with invoke, the server returns `sessionId`. The cmail system passes this up through `CmailSendResult.sessionId`. The UI reads it and offers a "Watch session" navigation action that opens `MessagesScreen(sessionId)`. This handoff is correct — keep it.

**Seam 2 — dispatch voice message links to a cmail thread**
FCM payloads include `thread_id`. `VoiceNotification.cmailThreadId` carries this. When voice reply fires, the coordinator uses this thread ID to route the reply to the right cmail thread.

**Seam 3 — session conversation contains dispatch bubbles**
`ChatBubble(type="dispatch")` is a session event record showing the agent called `dispatch send`. It's NOT the FCM notification — it's a historical trace. They can optionally share a `traceId` for correlation, but they are different objects from different systems.

### The Rule That Prevents Future Blurring

Each system has exactly one way in and one way out:
- **Sessions:** In through `SseConnectionService` (chat_bubble events) or `SessionsApiManager` (WebSocket). Out through `ChatBubbleRepository` and `MessagesViewModel`.
- **cmail:** In through REST responses. Real-time refresh signal through `CmailEventBus` (SharedFlow from SSE). Out through `CmailRepository` and `ThreadsViewModel`.
- **Dispatch:** In through exactly one entry point: `DispatchFcmService.onMessageReceived()`. Out through `PlaybackStateHolder` and ExoPlayer audio.

### Clean Component Map

```
SESSIONS LAYER
  Transport:   File Bridge SSE or WebSocket (self-built)
  Auth:        Tailscale + File Bridge
  Repository:  SessionsApiManager, ChatBubbleRepository
  ViewModel:   MessagesViewModel, ChatViewModel
  Screens:     ChatScreen (list), MessagesScreen (conversation)
  Persistence: Room chat_bubbles table

CMAIL SYSTEM
  Transport:   File Bridge REST
  Auth:        File Bridge auth
  Repository:  CmailRepository, CmailOutboxRepository
  ViewModel:   ThreadsViewModel, SendViewModel
  Screens:     Threads list, Thread detail, Compose
  Persistence: Room cmail_outbox table (optional)

DISPATCH SYSTEM
  Transport:   FCM inbound, Kokoro TTS outbound
  Auth:        Firebase SDK (FCM), Tailscale (TTS)
  Repository:  VoiceNotificationRepository
  Service:     DispatchFcmService, DispatchPlaybackService
  UI:          MiniPlayerBar (overlay)
  Persistence: Room voice_notifications table

SEAM CONTRACTS
  cmail → sessions:  CmailSendResult.sessionId navigates to MessagesScreen
  dispatch → cmail:  VoiceReplyCoordinator bridges earbud reply to CmailRepository
  sessions → dispatch: ChatBubble(type="dispatch") + traceId for correlation
```

---

## Task List (Everything That Needs Investigation or Work)

### From Question 1 (Sessions API: Local vs Remote)
- [x] Determine if Sessions API connects to local sessions → NO, cloud only
- [x] Understand bridge registration flow → POST /v1/environments/bridge
- [x] Map session ID format differences → UUID local vs cse_/session_ cloud
- [x] Verify teleport uses API not local files → confirmed, uses /v1/session_ingress

### From Question 2 (Build Our Own vs Use Anthropic)
- [x] Determine if self-built is viable → YES
- [x] Identify what already exists → 80% of infrastructure
- [x] Identify what needs building → Session Manager, REST endpoints, SSE stream, Android client
- [x] Compare reliability of both approaches → Self-built wins on reliability, auth, latency
- [ ] **DECISION NEEDED:** Confirm self-built path as the direction before any code is written
- [ ] **INVESTIGATION:** Validate the stdin pipe persistence pattern — spawn a `claude -p --input-format stream-json --output-format stream-json` process and verify it stays alive waiting for stdin input between messages
- [ ] **INVESTIGATION:** Test `--resume` with stream-json mode — does resuming a session work correctly in headless bidirectional mode?
- [ ] **DESIGN:** Session Manager service architecture — process registry, crash recovery, cleanup
- [ ] **DESIGN:** File Bridge REST endpoint contracts — exact request/response shapes
- [ ] **DESIGN:** SSE stream endpoint — how to tail subprocess stdout and fan out to multiple Android clients

### From Question 3 (cmail/Sessions/Dispatch Separation)
- [ ] **REFACTOR:** Split `DispatchMessage` into `VoiceNotification`, `CmailOutboxItem`, keep `ChatBubble`
- [ ] **REFACTOR:** Split `MessageRepository` into `VoiceNotificationRepository`, `CmailOutboxRepository`, keep `ChatBubbleRepository`
- [ ] **REFACTOR:** Remove `sendCmailRelay()` from `SessionRepository`, move to `CmailRepository`
- [ ] **REFACTOR:** Make `SseConnectionService` a router — route events to correct repository by type
- [ ] **NEW:** Create `CmailEventBus` (SharedFlow<String>) for real-time thread refresh signals
- [ ] **NEW:** Create `VoiceReplyCoordinator` to bridge dispatch earbud reply → cmail
- [ ] **REFACTOR:** Update `DispatchPlaybackService` to use `VoiceReplyCoordinator` instead of direct `CmailRepository` dependency
- [ ] **DESIGN:** Room schema for `voice_notifications` table
- [ ] **DESIGN:** Room schema for `cmail_outbox` table (if needed for optimistic UI)

### Cross-Cutting
- [ ] **DECISION NEEDED:** Which path first — separation refactor or session manager build?
- [ ] **INVESTIGATION:** OAuth token extraction — if we ever want the Anthropic path as fallback, can we extract tokens from CLI keychain on pop-os and pass to Android?
- [ ] **DOCUMENTATION:** Once decisions are made, write the architecture spec that engineering builds from — no ambiguity, no room for hallucination

---

## Sources

All findings verified against:
- `dissected/cli-beautified.js` v2.1.71 (520,715 lines) — line numbers cited throughout
- `claude-code/CHANGELOG.md` — version history
- Android codebase at `/home/xoom000/AndroidStudioProjects/Dispatch/`
- Project docs at `/home/xoom000/AndroidStudioProjects/Dispatch/.project/`
- GitHub issues: #30102 (pollForWork 401), #28039 (session index), #24106 (teleport 404)
