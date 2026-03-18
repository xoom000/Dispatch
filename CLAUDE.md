# Dispatch -- Project Memory

## Communication Method: Dispatch Voice Messages

**ALL COMMUNICATION WITH NIGEL GOES THROUGH DISPATCH. NO EXCEPTIONS.**

Every reply, every answer, every question, every update, every confirmation -- everything goes through `dispatch send`. Do not type responses in the chat. Nigel is driving between delivery stops. Dispatch speaks aloud so he can hear without looking at his phone.

```bash
dispatch send "Your message here" -f dispatch
```

This is not optional. This is not "for updates only." This is the ONLY way to communicate. The hook on every prompt reinforces this. Follow it.

Keep dispatch messages concise -- they're spoken aloud. One or two sentences max.

## Proven Patterns

### Audio Pipeline (v2.2 -- working, tested)
- Serial download executor (single thread) -- preserves message ordering
- Serial playback executor (single thread) -- no audio overlap
- Foreground service with AtomicInteger pending count -- survives background
- Retry once with 2s delay for Tailscale cold-start
- Piper fallback via speakBlocking() on playback executor -- no concurrent AudioTracks
- onComplete sentinel queued RIGHT AFTER playPcm on same executor

### Bilateral Communication (working, tested)
- Phone -> File Bridge -> cmail -> department inbox + agent invocation
- File Bridge at pop-os:8600 is the mobile gateway to cmail
- Sender identity is "nigel" (File Bridge cwd)
- Department list fetched from company.yaml via /cmail/departments

### Message History Database (v2.3)
- SQLite at ~/.config/dispatch/history.db on pop-os
- Every dispatch send is logged automatically (sender, message, voice, priority, timestamp, success/fail, file info)
- CLI: `dispatch history` with filters (-f sender, -p priority, -s search, --stats)
- File Bridge API: GET /dispatch/history and GET /dispatch/stats
- Android app History tab planned (uses File Bridge endpoints)

### Build & Deploy
```bash
./gradlew assembleDebug
scp app/build/outputs/apk/debug/app-debug.apk pixel9:~/storage/downloads/dispatch.apk
dispatch send "Build ready. <what changed>" -f dispatch
```
Always notify via dispatch after deploying a build.

### On-Device Logs (SSH-accessible)
App writes Timber logs to shared storage via FileLogTree. Accessible over SSH:
```bash
ssh pixel9 "cat ~/storage/shared/Download/dispatch-logs/dispatch-$(date +%Y-%m-%d).log"
ssh pixel9 "tail -50 ~/storage/shared/Download/dispatch-logs/dispatch-$(date +%Y-%m-%d).log"
```
- Location: `/storage/emulated/0/Download/dispatch-logs/dispatch-YYYY-MM-DD.log`
- Rotates daily by date
- Contains FCM receive times, audio pipeline events, HTTP timing, service lifecycle
- Survives app restarts (written to shared storage, not app-private)
- Also viewable in-app via the bug icon (top-right) -> LogViewerScreen

### cmail v3 Threading (2026-03-05)
- cmail now stores messages in `inbox/threads/{thread_id}/` alongside `inbox/new/`
- Agents invoked on threaded messages get full conversation transcript in prompt
- `cmail reply` auto-CCs all thread participants (reply-all)
- Nigel-gated cascade: CC'd agents only get invoked if sender is nigel/boardroom/ceo
- Agent-to-agent CC replies deliver silently (no invoke loop)
- Dispatch is unaffected -- FCM payload unchanged, app doesn't read cmail threads
- File Bridge /cmail/send works as before (cmail handles threading internally)
- New inbox folder: `inbox/threads/` alongside new/, read/, sent/, files/

### App Features (v2.5 -- 2026-03-05)
- Multi-select department broadcast from Send card (FlowRow + FilterChip)
- File attachment on Send card (file picker -> File Bridge /upload + cmail invoke)
- Tap-to-copy on message cards (tap = message text, long-press = sender + message)
- Replay button on message cards (re-speaks via GPU TTS with original voice)
- Sent message visibility: outgoing messages appear in message list with distinct styling
  - primaryContainer background vs surfaceVariant for incoming
  - Shows "You -> department" header with target department(s)
  - Includes attached file name in message body
  - No replay button (outgoing only)
- Dead code cleanup: AudioDownloader.kt removed

### Session Pipeline (Phase 4 -- 2026-03-05)
- Captures full Claude Code JSONL sessions into SQLite for the Activity tab
- 3-piece: SessionWriter (dg-it) -> session_watcher daemon -> File Bridge -> Android app
- Database: ~/.config/dispatch/sessions.db (WAL mode, ~104MB for 1030 sessions)
- Daemon: session-pipeline.service (systemd, auto-start, watchdog-based)
- CLI: `dg-sentinel pipeline-start [--backfill] [-d]`, `pipeline-stop`, `pipeline-status`
- File Bridge endpoints: GET /sessions, /sessions/active, /sessions/{id}, /sessions/{id}/debug
- Android: Activity tab with Sessions/Threads segmented toggle
- Record types stored: user, assistant, system, summary, queue-operation (NOT progress/file-history-snapshot)
- Skips raw_json to keep DB lean; extracts content_text, tool info, tokens from each record
- Idle detection: background thread marks sessions completed after 5min inactivity
- Backfill: processes all existing JSONL files (newest first, batch commits)

### Live Session Watching (Phase 5 -- 2026-03-05)
- After invoking an agent from Send tab, Watch button appears on the sent message card
- Tap Watch -> discovery phase polls fetchActiveSessions(dept=X) every 3s
- Matches session where startedAt is within [invokedAt-5s, invokedAt+180s]
- After 15s, also checks completed sessions (handles fast-finishing agents)
- Max 60 attempts (3 min timeout) with error + back button on failure
- Live watching: incremental fetchSessionDetail(sinceSequence=maxSeq) every 2.5s
- Auto-scrolls to bottom as new records arrive
- Stops polling when session.status != "active"
- Transient poll failures show banner but don't abort
- Reuses RecordItem/formatModelName/formatActivityTime from ActivityScreen (now internal)
- One DispatchMessage per department (not merged) with invoked/invokedDepartment/invokedAt fields
- CmailSendResult now parses response JSON for invoked/department fields
- LiveSessionScreen renders as fullscreen overlay (same pattern as LogViewerScreen)
- Zero backend changes -- uses existing File Bridge /sessions/active and /sessions/{id} endpoints

### Thread Reply Auto-Refresh (2026-03-06)
- ThreadDetailScreen now auto-polls after sending a reply that invokes an agent
- Polls every 3s for up to 2 minutes, stops when new message(s) appear
- "Waiting for response..." spinner shown at bottom of message list during polling
- Messages sorted by createdAt (handles dispatch cross-posts with position=0)
- Uses CmailSendResult.invoked flag to decide whether to poll (only polls on invoke)
- Thread list uses total_messages (merged cmail+dispatch count) over message_count (cmail-only)
- IT confirmed: File Bridge thread detail API returns merged messages (cmail + dispatch cross-posts) sorted by timestamp

### Thread-Aware Dispatch (Phase 1 -- 2026-03-06)
- thread_id flows end-to-end: CLI -> FCM payload -> Android app -> thread refresh
- dispatch CLI: `--thread-id` / `-T` flag, auto-reads DISPATCH_THREAD_ID env var
- dispatch history DB: v2 migration adds thread_id column + index
- cmail invoke: sets DISPATCH_THREAD_ID env var when invoking agent from threaded message
- Agents get thread context for free -- no prompt changes needed, env var auto-picked up
- FCM payload: includes thread_id when present
- Android: DispatchMessage.threadId parsed from FCM, MessageRepository emits threadRefreshEvents
- ThreadDetailScreen observes SharedFlow -- FCM push triggers instant refresh (1s delay for merge)
- Polling still works as fallback for legacy messages without thread_id
- File Bridge: exact thread_id match for merge (phase 1), time-window heuristic for legacy (phase 2)
- File Bridge dispatch/history endpoint now includes thread_id in response

### Room Database — Message Persistence (Phase 2 -- 2026-03-06)
- Messages now survive app crashes, process death, and restarts
- Room database at dispatch.db on device with MessageEntity table
- MessageRepository backed by Room: loads from DB on init, persists async on addMessage()
- StateFlow remains the reactive UI source — Room is the durable layer underneath
- Async writes via internal CoroutineScope (SupervisorJob + Dispatchers.IO)
- Fire-and-forget persistence: StateFlow updates instantly, Room write is non-blocking
- Auto-trim to 100 messages (up from 50) on insert
- Destructive migration fallback — this is a cache, pop-os dispatch history DB is source of truth
- No TypeConverters needed — list fields stored as comma-separated strings in entity
- Hilt DI: AppModule provides DispatchDatabase + MessageDao, injected into MessageRepository
- clearMessages() clears both StateFlow and Room
- threadRefreshEvents SharedFlow unchanged from Phase 1

### Optimistic UI + FCM Event Bus (Phase 3 -- 2026-03-06)
- Thread replies appear INSTANTLY as a bubble before server roundtrip completes
- Optimistic ThreadMessage inserted into detail.messages with synthetic ID ("optimistic_*")
- On API success: message stays (server reconciles on next fetch), awaitingAgent mode if invoked
- On API failure: optimistic message rolled back, reply text restored, toast shown
- FCM push via threadRefreshEvents is the PRIMARY thread update mechanism
- Replaced 40-poll busy loop (120s) with deferred safety net (max 2 checks at 30s and 60s)
- Safety net only fires if FCM push doesn't arrive — typically FCM beats it in <10s
- Thread list auto-refreshes when threadRefreshEvents fires (observes SharedFlow)
- ThreadListScreen only observes while visible (mutual exclusion with ThreadDetailScreen)
- Net HTTP reduction: from ~40 polls per invoke to 0-2 safety checks
- awaitingAgent state (renamed from awaitingResponse) drives the "Waiting for response..." spinner

### Event Orchestrator (Phase 4 -- 2026-03-06)
- Unified event store at ~/.config/dispatch/events.db on pop-os (WAL mode)
- Writers (dispatch, cmail, session pipeline) POST fire-and-forget to File Bridge /events/emit
- SSE endpoint GET /events/stream pushes real-time events to Dispatch app when foregrounded
- Supports Last-Event-ID header for reconnection (replays missed events from DB)
- 15-second heartbeat keeps connections alive through proxies
- GET /feed endpoint provides chronological timeline with filters
- Event types: dispatch_message, cmail_message, session_started, session_completed
- Android EventStreamClient: lifecycle-aware (@Singleton, DefaultLifecycleObserver)
  - Connects on foreground (ProcessLifecycleOwner onStart)
  - Disconnects on background (onStop)
  - Auto-reconnects on SSE failure (5s delay)
  - Tracks lastEventId for seamless reconnection
- FCM and SSE have DISTINCT responsibilities:
  - FCM: message delivery + audio playback (works in background)
  - SSE: thread/activity refresh signals (foreground only)
  - No dedup needed -- they don't create the same data
- Key gap filled: cmail thread replies from agents now push to phone via SSE
- Dispatch CLI hook: db.py log_message() -> POST localhost:8600/events/emit (2s timeout)
- Cmail CLI hook: db.py insert_message() -> POST 100.122.241.82:8600/events/emit (2s timeout)
- Session pipeline hooks: ensure_session() emits session_started, IdleDetector emits session_completed
- Dependencies added: sse-starlette>=2.0.0 (File Bridge), okhttp-sse + lifecycle-process (Android)
- CLI tool: `dispatch events` — query event feed and stream live SSE

### Dispatch Events CLI
```bash
dispatch events                        # Last 20 events
dispatch events -n 50                  # Last 50 events
dispatch events -t dispatch_message    # Filter: dispatch only
dispatch events -t cmail_message       # Filter: cmail only
dispatch events -t session_started     # Filter: session starts
dispatch events -d engineering         # Filter: by department
dispatch events -T <thread_id>         # Filter: by thread
dispatch events --since 2026-03-06     # Events since date
dispatch events --tail                 # Live stream (Ctrl+C to stop)
dispatch events --tail -t cmail_message  # Live stream, cmail only
dispatch events --status               # Diagnostics: event counts, SSE subscribers, source breakdown
```
**Diagnostics (`--status`)** answers: Is the phone connected to SSE? Are events flowing? Which writers are active? Shows total/24h/1h event counts, SSE subscriber count, breakdown by type and source, and the latest event.

### Context Window Visibility (Phase 5 -- 2026-03-06)
- Session pipeline extracts context_tokens from API usage data (input + cache_creation + cache_read)
- Sessions table has context_tokens (INT) and context_pct (REAL) columns
- Updated on every assistant record with usage data (monotonically increasing)
- File Bridge /sessions and /sessions/active return context_tokens + context_pct
- Android Activity tab shows colored context bar on each session card
  - Green < 60%, Orange 60-80%, Red > 80%
  - Text label: "ctx: N%"
- Max context window: 200K tokens (Opus/Sonnet/Haiku)

### Agent Flight Recorder (Phase 6 -- 2026-03-06)
- Real-time telemetry from all agent sessions via Claude Code hooks
- Single script: `tools/hooks/flight-recorder-hook.py` handles 5 event types
- Events emitted fire-and-forget to Event Orchestrator (File Bridge /events/emit)
- Streams to Dispatch app via existing SSE pipe (silent, no phone notifications)
- **Event types:**
  - `tool_used` (PostToolUse) -- every tool call with human-readable summary
  - `tool_failed` (PostToolUseFailure) -- tool execution errors
  - `session_ended` (SessionEnd) -- agent death detection
  - `session_compacting` (PreCompact) -- proves compaction actually happened
  - `agent_idle` (Notification idle_prompt) -- agent idle 60+ seconds
- Department auto-detected from cwd (working directory name)
- Catches agent confabulation: no PreCompact event = no compaction happened
- Catches one-shot death: SessionEnd fires when agent terminates after dispatch
- Catches fake "working": no tool_used events = agent is idle
- Wired into ~/.claude/settings.json (user-level, all agents inherit)
- New sessions required to pick up hook config (snapshot at session start)
- Query: `dispatch events -t tool_used`, `dispatch events -t session_ended`, etc.
- **Android UI:** Activity tab -> Events segment (3rd tab: Sessions | Threads | Events)
  - Live scrolling feed of all flight recorder events, newest first
  - Color-coded by type: green (tool), red (failed), orange (ended), blue (compacting), purple (idle)
  - Monospace summary text for at-a-glance readability
  - Auto-refreshes via SSE when app is foregrounded (EventStreamClient emits refresh signal)
  - Manual refresh button in header
  - Fetches from File Bridge GET /feed endpoint (100 events per load)

### Full History Archive (2026-03-07)
- Inbox tab now has 3 segments: Messages | Threads | History
- History segment fetches from pop-os dispatch history DB via File Bridge GET /dispatch/history
- Shows every dispatch message ever sent (currently ~985 messages)
- Paginated infinite scroll (loads 40 at a time, fetches more as you scroll)
- Text search across message content (keyboard search action)
- Sender filter chips (auto-populated from actual message data)
- Tap to copy message, long-press to copy with sender
- Replay button (re-speaks via GPU TTS with original voice)
- Failed messages shown with error container + error detail text
- Priority badges for non-normal messages
- "All N messages loaded" indicator at bottom when fully scrolled
- Clear filters button on empty search results
- Data model: HistoryMessage (data/HistoryModels.kt)
- Network: FileTransferClient.fetchDispatchHistory() with sender/search/priority/limit/offset params
- No new backend changes -- uses existing File Bridge /dispatch/history endpoint

### Invoke Session Resume Fix (2026-03-07)
- **Root cause**: cmail _invoke_agent() session lookup (sessions.db) was failing silently
- Session pipeline's idle detector marks interactive sessions as "completed" after 5min idle
- When lookup returned None, a brand new session spawned for every phone message
- This caused: session fragmentation in Activity tab, thread_id scatter, double delivery
- **Fix 1 (session fallback)**: When DB lookup fails, reads {agent_home}/.claude/current_session_id
  - Written by session-start-hook.py on every session start
  - Always has the most recent session_id for the department
  - Falls back gracefully if file doesn't exist
- **Fix 2 (thread persistence)**: DISPATCH_THREAD_ID saved to {agent_home}/.claude/dispatch_thread_id
  - New messages (thread_position=1) reuse saved thread_id for conversation continuity
  - Explicit thread replies (thread_position>1) use the reply's thread_id
  - Prevents thread fragmentation (one thread per department conversation)
- **File**: tools/cmail/src/cmail/core.py, _invoke_agent() function
- **Live**: editable install, changes effective immediately
- **Remaining**: double delivery still exists (message in user prompt + inject hook), minor cosmetic issue

### Accessibility Actions (v3.2 -- 2026-03-07)
- Remote phone control via AccessibilityService + FCM action payloads
- FCM payload: `type=action`, `action=call|text|read_messages|dump_tree`
- DispatchFcmService routes action payloads to AccessibilityService (skips audio pipeline)
- Hybrid approach: intents open target app, accessibility navigates and taps
- getLaunchIntent() helper with fallback to known component intents (Android 11+ package visibility)
- Retry loop: up to 12 attempts at 500ms intervals to find target elements
- isCorrectAppRoot() prevents searching wrong app's accessibility tree
- Notification posted on success/failure
- User must manually enable service: Settings > Accessibility > Dispatch
- Static instance pattern: FCM calls DispatchAccessibilityService.executeAction()
- **Actions:**
  - `call` -- Opens dialer, taps call button
  - `text` -- Opens Messages compose, populates text via ACTION_SET_TEXT, taps send
  - `read_messages` -- 5-state machine: find in list -> search -> navigate -> extract messages -> write JSON
  - `dump_tree` -- Dumps full accessibility tree to JSON file on shared storage
- **CLI Commands:**
  - `dispatch action call <number>`
  - `dispatch action text <number> "<message>"`
  - `dispatch action read <contact> [--json] [--timeout N]` -- reads SMS conversation for agents
  - `dispatch action dump <label> [--app pkg]` -- diagnostic tree dump
  - `dispatch action map <label> [-o file]` -- dump + parse into readable map
- **Files:**
  - `accessibility/PhoneAction.kt` -- sealed class with fromFcmData() parser (Call, SendText, ReadMessages, DumpTree)
  - `accessibility/DispatchAccessibilityService.kt` -- service with UI automation (~1100 lines)
  - `res/xml/accessibility_service_config.xml` -- service config (canRetrieveWindowContent, canPerformGestures)
  - `fcm/DispatchFcmService.kt` -- action routing
  - CLI: `dispatch/cli.py` action subcommands, `dispatch/core.py` send_action()
  - **Map:** `docs/google-messages-a11y-map.md` -- comprehensive accessibility tree documentation
- **Setup on phone:** Install build, then Settings > Accessibility > Downloaded apps > Dispatch > toggle ON
- **Known limitations:**
  - Button IDs are Pixel/Google apps specific -- may need adjustment for other devices
  - One action at a time (new action cancels in-flight one)
  - No confirmation dialog bypass (e.g., emergency call warnings)
  - getLaunchIntentForPackage returns null from AccessibilityService -- fixed with explicit component fallback

### Google Messages Accessibility Map (2026-03-07)
- Full tree dumps captured and documented: `docs/google-messages-a11y-map.md`
- Conversation list: traditional Views with reliable resource IDs
  - `action_zero_state_search` = search button
  - `list` = scrollable RecyclerView of conversations
  - `swipeableContainer` = clickable conversation row
  - `conversation_name` / `conversation_snippet` / `conversation_timestamp` = text fields
  - `unread_badge_view_with_message_count_stub` = unread count
- Conversation thread: Jetpack Compose, uses contentDescription as primary identifier
  - `message_list` = scrollable message container
  - `message_text` = both message content AND date separators
  - `contentDescription` pattern: "{Sender} said  {message} {Day} {HH:mm} ."
  - `compose_message_text` = editable compose field (EditText even in Compose UI)
  - `ComposeRowIcon:Shortcuts` = attachment button
  - `ComposeRowIcon:Gallery` = photo picker
  - `Compose:Draft:Send` = send button (desc changes when text populated)
- RCS detection: `rcs_badge` ImageView, compose field text "RCS message" vs "Text message"

### Cmail Invoke Guard (2026-03-07)
- Fixed: cmail spawning duplicate `claude -p` processes when interactive session already running
- Root cause: every phone message triggered _invoke_agent() which spawned a NEW claude process
- Both the interactive session AND the -p process would process the message and send dispatch replies
- Fix: _invoke_agent() now checks /proc for running claude processes in the department's CWD
- If an interactive session (non `-p`) is detected, invoke is skipped (message already in inbox)
- The UserPromptSubmit hook delivers the message to the running session instead
- File: `tools/cmail/src/cmail/core.py`, _invoke_agent() function

### Backend Refactor Compatibility (2026-03-09)
- 4 backend refactors assessed: cmail V2, cmail modular split, dispatch CLI refactor, File Bridge modular routers
- **File Bridge 307 redirect**: Modular routers use `prefix="/sessions"` + `@router.get("/")` which causes 307 redirect from `/sessions?params` to `/sessions/?params`. Fixed in FileTransferClient by adding trailing slash.
- **SSE event flood**: Flight recorder emits ~2 events/sec (20K+ total, 6500/hour). Every event triggered immediate refresh → CPU/network flood → process death → FCM stopped. Fixed with 3-second debounce in EventStreamClient. Flight recorder events (`tool_used`, `tool_failed`, `session_ended`, `session_compacting`, `agent_idle`, `session_started`, `session_completed`) are coalesced into a single refresh signal per 3s window.
- **Session detail load-from-end**: Sessions with 3500+ records previously loaded 500 at a time from sequence 1 (oldest). Now loads latest 200 records using `sinceSequence = max(0, recordCount - INITIAL_LOAD_SIZE)`. "Load older" button at top fetches previous 200 records.
- **Session cache**: SessionsRoot maintains `mutableMapOf<String, SessionCache>()` at the navigation level. Back-navigation saves current state (records, maxSequence, oldestSequence, sessionInfo, status). Re-entering a session restores from cache instantly, resumes polling.
- **Cmail V1 table renamed**: `messages` → `messages_v1_archive`. File Bridge V1 fallback queries wrong table name. Only affects OLD threads without V2 messages. App doesn't call affected endpoints directly — minor.
- **FCM payload**: dispatch CLI now always includes `thread_id` and uses `AndroidConfig(priority="high")`. App already handles both — no Android changes needed.

### Thread Data Merge (2026-03-09)
- Threads tab redesigned: 3-level navigation (Departments → Department Threads → Thread Detail)
- Like Claude Code's "Resume Session" picker — agents first, then their threads
- File: `ui/screens/ThreadsScreen.kt` (complete rewrite)
- **Backend fix**: File Bridge `/cmail/threads` now merges two data sources:
  1. Cmail threads (phone ↔ agent conversations via cmail)
  2. Dispatch-only threads (agent → phone via `dispatch send`, no cmail involved)
- Dispatch-only threads were previously invisible (only existed in dispatch history DB)
- Cmail threads with dispatch messages had stale metadata (count/timestamp only reflected cmail)
- Fix: bulk query dispatch DB for thread metadata, merge into cmail thread entries
- Thread detail fallback: `_dispatch_only_thread_detail()` builds synthetic thread from dispatch history
- File Bridge: `routers/cmail.py` — `_get_dispatch_only_threads()`, `_dispatch_only_thread_detail()`, enrichment loop in `cmail_threads()`
- Pagination moved to Python (merge both sources, sort, slice) — fine for <1000 threads per department

### Known Gap: Agent File Attachments
- When dispatch CLI sends files (-a), they go through File Bridge staging -> FCM with download URL -> app shows download button. This works.
- When agents send files via cmail (-a), files go to recipient inbox/files/ via rsync. The Dispatch app never sees these -- no FCM, no download button.
- Fix needed: cmail mobile sync should stage files on File Bridge and dispatch the URL.

## Known Working State
- Foreground service: tested with 3-minute background soak
- Multi-message queuing: tested with 3-message burst, clean serial playback
- Bilateral send: tested phone -> dispatch department -> cmail delivery
- Tailscale retry: tested, connects 120-140ms when warm
- History database: tested with filtered queries and stats
- File Bridge history API: tested, returns JSON with pagination
- Session pipeline: backfilled 1030 sessions, 216K records, live capture via systemd daemon
- Activity tab: sessions list loads with department badges, detail view shows user/assistant/tool records
- Live session watching: confirmed working (Watch button -> discovery -> live record streaming)
- Thread reply auto-refresh: deployed (needs testing -- invoke reply in thread, verify agent response appears)
- Thread-aware dispatch: deployed (CLI sends thread_id, DB stores it, FCM carries it, app parses it -- needs e2e test with real threaded invoke)
- Room database: deployed (messages persist across restarts -- install, receive messages, kill app, reopen to verify)
- Optimistic thread reply: deployed (reply in thread, bubble appears instantly, rolls back on failure)
- FCM event bus: deployed (agent dispatch with thread_id triggers instant thread refresh, no polling)
- Thread list auto-refresh: deployed (FCM push triggers thread list update when visible)
- Event orchestrator: verified (emit -> SSE -> feed all working, dispatch CLI hook fires, session hooks fire)
- Android SSE client: deployed (needs install + foreground test with thread invoke)
- Events CLI: tested (dispatch events, filters, --tail streaming all working)
- Full history archive: deployed (Inbox -> History tab, fetches all ~985 messages from pop-os, searchable with sender filters)
- Accessibility actions: text send verified working (compose field population via ACTION_SET_TEXT), read/dump need re-test after intent fix
- Google Messages mapped: 3 tree dumps captured (conv list, thread view, compose), full documentation at docs/google-messages-a11y-map.md
- Cmail invoke guard: live (editable install), prevents duplicate -p process spawns when interactive session running
- Thread list: shows ALL threads including agent-to-agent (no participant filtering)
- Context visibility: deployed (session pipeline tracks %, File Bridge serves it, app shows colored bar)
- Flight recorder: verified (all 5 event types emit to orchestrator, tool_used fires live on active sessions)
- SSE debouncing: deployed (3s window coalesces flight recorder events, prevents CPU/network flood)
- Session load-from-end: deployed (latest 200 records on open, "Load older" button for history)
- Session cache: deployed (back-navigation preserves loaded records, re-entry restores instantly)
- File Bridge 307 fix: deployed (trailing slash on /sessions/ URL)
- Backend refactor compat: assessed all 4 refactors (cmail V2, cmail split, dispatch CLI, File Bridge routers) — app compatible
- Thread data merge: deployed (dispatch-only threads visible, cmail threads enriched with dispatch metadata, 3-level nav on app)
