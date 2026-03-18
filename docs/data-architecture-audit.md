# Dispatch Data Architecture Audit

**Date:** 2026-03-06
**Author:** Dispatch Agent
**Purpose:** Map every database, every field, every connection point across the Dispatch ecosystem. Identify what exists, what's connected, what's not, and what needs to change.

---

## The Three Databases

All three exist on pop-os. File Bridge reads from all three as a stateless gateway.

### 1. Cmail Messages DB

**Path:** `~/.config/cmail/messages.db`
**Written by:** `cmail` CLI (tools/cmail/)
**Size:** ~188 KB, 188 messages, 181 threads
**Mode:** WAL, foreign_keys=ON

**Table: messages**

| Column | Type | Purpose |
|--------|------|---------|
| id | INTEGER PK | Auto row ID |
| message_id | TEXT | Unique msg ID: `msg_{timestamp}_{sender}` |
| thread_id | TEXT | UUID linking conversation |
| position | INTEGER | Sequential position in thread (1, 2, 3...) |
| sender | TEXT | Department that sent |
| recipient | TEXT | Department receiving this copy |
| cc | TEXT | Comma-separated CC list |
| subject | TEXT | Subject line |
| body | TEXT | Message content |
| priority | TEXT | low / normal / urgent |
| delivery | TEXT | direct / cc / bcc |
| read | INTEGER | 0=unread, 1=read (per recipient) |
| created_at | TEXT | ISO 8601 UTC |
| attachments | TEXT | JSON array of {name, path} objects |

**Key design:** One row per delivery. Same message_id appears multiple times for CC/BCC.

**Table: threads** (materialized view, maintained by app logic)

| Column | Type | Purpose |
|--------|------|---------|
| thread_id | TEXT PK | UUID |
| subject | TEXT | Thread subject |
| participants | TEXT | JSON array of department names |
| message_count | INTEGER | Count of DISTINCT message_ids |
| created_at | TEXT | Thread creation time |
| last_activity | TEXT | Most recent message time |

**Indexes:** idx_thread (thread_id, position), idx_recipient (recipient, read), idx_sender, idx_created, idx_message_id

---

### 2. Dispatch History DB

**Path:** `~/.config/dispatch/history.db`
**Written by:** `dispatch` CLI (tools/dispatch/)
**Size:** 401 messages
**Mode:** WAL, foreign_keys=ON

**Table: messages**

| Column | Type | Purpose |
|--------|------|---------|
| id | INTEGER PK | Auto row ID |
| timestamp | TEXT | ISO 8601 UTC with milliseconds |
| sender | TEXT | Department/agent name (lowercase) |
| message | TEXT | Full message text |
| voice | TEXT | Kokoro voice ID (am_puck, am_michael, etc) |
| priority | TEXT | low / normal / urgent |
| success | INTEGER | 0=failed, 1=succeeded |
| error | TEXT | Error message if failed |
| file_name | TEXT | Attached filename (first file only) |
| file_url | TEXT | Download URL on File Bridge |
| file_size | INTEGER | File size in bytes |
| created_at | TEXT | DB insertion time |

**Indexes:** idx_messages_timestamp, idx_messages_sender, idx_messages_priority

**Limitation:** Single file per row. Multi-attachment FCM payloads only log the first.

---

### 3. Session Pipeline DB

**Path:** `~/.config/dispatch/sessions.db`
**Written by:** session_watcher daemon (dg-sentinel via dg-it)
**Size:** 1,038 sessions, 216,708 records
**Mode:** WAL

**Table: sessions**

| Column | Type | Purpose |
|--------|------|---------|
| session_id | TEXT PK | UUID from Claude Code JSONL filename |
| department | TEXT | Mapped from project_key |
| project_key | TEXT | Claude Code project directory name |
| summary | TEXT | Session summary (from summary records) |
| model | TEXT | Claude model (claude-opus-4-6) |
| started_at | TEXT | ISO 8601 |
| last_activity | TEXT | ISO 8601, updated on each record |
| record_count | INTEGER | Total records |
| status | TEXT | active / completed |
| git_branch | TEXT | Git branch at session start |
| cwd | TEXT | Working directory |

**Table: session_records**

| Column | Type | Purpose |
|--------|------|---------|
| id | INTEGER PK | Auto row ID |
| session_id | TEXT | FK to sessions |
| agent_id | TEXT | 'main' or 'agent-{uuid}' for subagents |
| sequence | INTEGER | Line number in JSONL (dedup key) |
| record_type | TEXT | user / assistant / system / summary / queue-operation |
| timestamp | TEXT | ISO 8601 |
| model | TEXT | Claude model |
| content_text | TEXT | Extracted content (max 5000 chars) |
| tool_name | TEXT | Tool used (Bash, Read, etc) |
| tool_input | TEXT | Tool input/command |
| tool_status | TEXT | success / error |
| is_error | INTEGER | Boolean error flag |
| tokens_in | INTEGER | Input tokens |
| tokens_out | INTEGER | Output tokens |
| raw_json | TEXT | ALWAYS NULL (space optimization) |

**UNIQUE constraint:** (session_id, agent_id, sequence)

**Indexes:** idx_records_session_seq, idx_sessions_dept_activity, idx_sessions_status

---

## File Bridge (The Gateway)

**Path:** tools/file-bridge/
**Host:** pop-os:8600
**Has own database:** NO. Reads from all three DBs above (read-only, WAL mode).

### Endpoints Summary

**Cmail Gateway:**
- POST /cmail/send -- relay messages from phone to cmail (with optional thread_id, invoke)
- GET /cmail/departments -- list departments from company.yaml
- GET /cmail/threads -- list threads (queries cmail DB)
- GET /cmail/threads/{id} -- thread detail with MERGED cmail + dispatch messages
- GET /cmail/messages -- query cmail messages
- GET /cmail/stats -- cmail statistics

**Dispatch History:**
- GET /dispatch/history -- paginated message history (queries dispatch DB)
- GET /dispatch/stats -- message statistics

**Sessions:**
- GET /sessions -- list sessions with filters
- GET /sessions/active -- active sessions only
- GET /sessions/{id} -- session detail with incremental records (since_sequence)
- GET /sessions/{id}/debug -- aggregated session debug stats

**Files:**
- POST /stage -- stage file for mobile download
- POST /upload -- upload from phone to department inbox
- GET /files/{id}/{name} -- download staged file
- GET /files -- list staged files

**Config:**
- GET /config/voice-map -- department-to-voice mapping
- PUT /config/voice-map -- update voice assignment

### Thread Merging Logic (GET /cmail/threads/{id})

This is the ONE place where cmail and dispatch data gets stitched together:

1. Query cmail messages table for thread_id
2. Extract agent participant names (exclude nigel)
3. Query dispatch history DB for messages FROM those agents SINCE thread start
4. Merge dispatch messages with delivery="dispatch"
5. Sort by created_at
6. Window: thread start to 30 min after last cmail activity

This is READ-TIME merging. Dispatch messages are never written to cmail's DB. The merge happens every time you fetch a thread.

---

## FCM Payload (The Push Channel)

**Sent by:** dispatch CLI (core.py) via Firebase Admin SDK
**Received by:** DispatchFcmService on Android

**Data-only message (no notification block):**

| Field | Required | Example |
|-------|----------|---------|
| sender | yes | "engineering" |
| message | yes | "Build deployed." |
| priority | yes | "normal" |
| voice | yes | "am_michael" |
| timestamp | yes | "2026-03-06T16:00:00.000Z" |
| file_url | no | "http://100.122.241.82:8600/files/abc/report.pdf" |
| file_name | no | "report.pdf" |
| file_size | no | "12345" |
| file_urls | no | comma-separated (multi-file) |
| file_names | no | comma-separated (multi-file) |
| file_sizes | no | comma-separated (multi-file) |

**What's NOT in the FCM payload:**
- thread_id (no way to associate a dispatch message with a cmail thread at push time)
- session_id (no way to link to the agent session that produced it)
- message_id (no cmail message_id reference)
- in_reply_to (no parent message reference)

---

## The Android App's Data Model

**MessageRepository:** In-memory StateFlow<List<DispatchMessage>>. No persistence. Crash = gone.

**DispatchMessage fields:**

| Field | Source | Persisted |
|-------|--------|-----------|
| sender | FCM payload | NO (RAM only) |
| message | FCM payload | NO |
| priority | FCM payload | NO |
| voice | FCM payload | NO |
| timestamp | FCM payload | NO |
| fileUrl/fileName/fileSize | FCM payload | NO |
| isOutgoing | set locally | NO |
| targetDepartment | set locally | NO |
| invoked | CmailSendResult | NO |
| invokedDepartment | CmailSendResult | NO |
| invokedAt | System.currentTimeMillis | NO |
| sessionId | CmailSendResult | NO |

Everything is volatile. Nothing survives process death.

---

## GAP ANALYSIS

### Gap 1: No Unified Identity

Each database has its own message ID format:
- cmail: `msg_{timestamp}_{sender}`
- dispatch: auto-increment integer
- sessions: `{uuid}` (session) + sequence number (record)

There is NO cross-reference between them. A dispatch voice message cannot be linked back to the cmail thread that triggered it. A session cannot be linked to the dispatch messages it produced.

### Gap 2: No Thread Awareness in Dispatch

The dispatch CLI has zero concept of threads. When an agent dispatches a voice message, it doesn't know (or pass along) which cmail thread triggered the work. The FCM payload has no thread_id field.

File Bridge does read-time merging by guessing: "dispatch messages from agent X between time A and B probably belong to this thread." This is fragile. It works for simple cases but breaks when:
- An agent dispatches multiple messages
- Two threads involve the same agent within the time window
- The agent dispatches before cmail records the thread

### Gap 3: No Push for Thread Updates

When an agent responds in a cmail thread, the only push to the phone is the dispatch voice message (FCM). But that FCM payload has no thread_id, so the app can't update the thread view. We resort to polling.

If FCM included `thread_id`, the app could:
- Instantly refresh the specific thread that got a response
- Show the response in the thread view AND play the audio simultaneously
- Zero polling

### Gap 4: No Message Persistence on Phone

MessageRepository is RAM-only. Every crash, every background kill, every app restart wipes the message list. The data exists on pop-os (dispatch history DB), but the app has no local cache.

Options:
- Room database on Android (local cache)
- Fetch from File Bridge /dispatch/history on startup (already exists, just not used)
- Both (Room as cache, File Bridge as source of truth)

### Gap 5: Dispatch History Has No Threading

The dispatch history DB (`history.db`) has no thread_id column. Even if we wanted to link dispatch messages to threads at write time, there's nowhere to store it.

### Gap 6: One-Way Data Flow

Phone -> File Bridge -> cmail -> agent (works)
Agent -> dispatch CLI -> FCM -> phone (works for audio)
Agent -> cmail thread -> phone thread view (BROKEN -- no push, relies on polling/merging)

The missing link is: agent's cmail response -> immediate push to phone with thread context.

### Gap 7: File Bridge Thread Merge is Fragile

The 30-minute time window + sender matching is a heuristic. It can:
- Miss late responses (agent responds after 30 min window)
- Include unrelated dispatch messages (agent sends about something else)
- Duplicate messages if they appear in both cmail and dispatch

---

## WHAT WOULD FIX THIS

### Option A: Thread-Aware Dispatch (Minimal Change)

Add `thread_id` to the dispatch pipeline:

1. **dispatch CLI:** Add `--thread-id` parameter to `dispatch send`
2. **dispatch history DB:** Add `thread_id` column to messages table
3. **FCM payload:** Include `thread_id` when present
4. **Android app:** When FCM arrives with `thread_id`, refresh that thread
5. **Agents:** When responding to a cmail thread, pass `--thread-id` to dispatch

This gives the phone instant thread updates via the existing FCM push channel. No new infrastructure. File Bridge merge logic becomes optional (nice-to-have vs required).

Cost: Small. CLI change + DB migration + FCM field + Android handler.

### Option B: Room Database on Android (Message Persistence)

Add local persistence so messages survive crashes:

1. **Room DB** with DispatchMessage entity
2. **On FCM receive:** Insert into Room before playing audio
3. **On app start:** Load from Room (instant) + sync with File Bridge (background)
4. **MessageRepository:** Backed by Room DAO instead of MutableList

This fixes the "crash loses everything" problem. Room is the standard Android answer.

Cost: Medium. New dependency, entity, DAO, migration strategy.

### Option C: FCM as the Thread Event Bus (Ideal)

Combine A + B + optimistic UI:

1. Thread-aware dispatch (Option A)
2. Room persistence (Option B)
3. **Optimistic UI:** When Nigel sends a reply, insert it into Room + UI instantly
4. **FCM-triggered refresh:** When agent's dispatch arrives with thread_id, Room updates, thread view refreshes
5. **No polling anywhere.** Every update is either local (optimistic) or pushed (FCM)

This matches the DG Portal pattern (Supabase Realtime) but uses FCM instead of WebSockets. Same result: instant updates, no polling.

Cost: Medium-High. But it's the right architecture.

### Option D: Full Orchestrator (Long-term)

Unify all three databases into a single event store:

1. Every cmail message, dispatch message, and session event flows through one system
2. File Bridge becomes a write-through cache, not just a read gateway
3. Single API surface for the app
4. WebSocket or SSE for real-time push (supplement FCM for when app is open)

This is the "do it right" answer but it's a major infrastructure project. Not where we start.

---

## RECOMMENDED PATH

**Phase 1: Thread-Aware Dispatch (Option A)**
- Unblocks real-time thread updates via existing FCM
- 1-2 days of work across CLI + Android
- Biggest bang for the buck

**Phase 2: Room Database (Option B)**
- Unblocks message persistence
- 1 day of work on Android
- Fixes the "crash loses everything" problem

**Phase 3: Optimistic UI + FCM Event Bus (Option C)**
- Combines Phase 1 + 2 into the ideal pattern
- Removes all polling from the app
- Production-grade real-time messaging

Phase 4 (Option D) is aspirational. Not needed until the system outgrows File Bridge.

---

## REFERENCE: Data Flow Diagram

```
+------------------+     +------------------+     +------------------+
|   Cmail DB       |     | Dispatch History  |     |  Sessions DB     |
|  messages.db     |     |   history.db      |     |  sessions.db     |
|                  |     |                   |     |                  |
| messages         |     | messages          |     | sessions         |
| threads          |     | (flat, no threads)|     | session_records  |
| (thread_id link) |     |                   |     | (no thread link) |
+--------+---------+     +--------+----------+     +--------+---------+
         |                         |                         |
         +-------------------------+-------------------------+
                                   |
                          +--------v---------+
                          |   FILE BRIDGE    |
                          |   (stateless)    |
                          |   pop-os:8600    |
                          |                  |
                          | Reads all 3 DBs  |
                          | Merges at read   |
                          | time (fragile)   |
                          +--------+---------+
                                   |
                          HTTP over Tailscale
                                   |
                          +--------v---------+
                          |  DISPATCH APP    |
                          |  (Pixel 9)       |
                          |                  |
                          | MessageRepository|
                          | (RAM ONLY)       |
                          | No local DB      |
                          | No persistence   |
                          +------------------+

Push Channel (independent):
  dispatch CLI -> FCM -> DispatchFcmService
  (no thread_id, no message linking)
```

---

## FILES REFERENCED

- Cmail source: /home/xoom000/digital-gnosis/tools/cmail/src/cmail/
- Dispatch CLI: /home/xoom000/digital-gnosis/tools/dispatch/src/dispatch/
- File Bridge: /home/xoom000/digital-gnosis/tools/file-bridge/src/file_bridge/
- Session pipeline: /home/xoom000/digital-gnosis/tools/dg-it/src/dg_it/sentinel/
- Android app: /home/xoom000/AndroidStudioProjects/Dispatch/
- Cmail DB: ~/.config/cmail/messages.db
- Dispatch DB: ~/.config/dispatch/history.db
- Sessions DB: ~/.config/dispatch/sessions.db
