# DG File Bridge API — Endpoint Reference

**Base URL:** `http://pop-os:PORT` (File Bridge service on pop-os)
**Auth:** All requests require an `X-API-Key` header with a valid API key.

---

## Table of Contents

1. [Conversation](#conversation)
2. [Messaging](#messaging)
3. [Voice / TTS](#voice--tts)
4. [Events / Real-Time](#events--real-time)
5. [Observability](#observability)
6. [Topology / Graph](#topology--graph)
7. [Snapshots](#snapshots)
8. [Utility](#utility)
9. [Gemini](#gemini)

---

## Conversation

| Method | Path | Description |
|--------|------|-------------|
| POST | `/chat/stream` | Spawn Claude in stream-json mode; relay token, sentence, and tool events as SSE to the client. |
| GET | `/chat/stream/active` | List active streaming subprocess sessions with stream_id, pid, and alive status. |
| DELETE | `/chat/stream/{stream_id}` | Kill an active streaming session by ID. |
| GET | `/sessions/` | List sessions from JSONL files; supports dept filter and pagination. |
| GET | `/sessions/active` | List currently active sessions only. |
| GET | `/sessions/for-dispatch` | Session list optimized for the Dispatch app; includes cmail aliases. |
| GET | `/sessions/{session_id}/chat` | Return session conversation as chat bubbles (nigel/agent/dispatch/tool) with tail and before pagination. |
| POST | `/sessions/{session_id}/command` | Execute a named command on a specific Claude Code session via expect script. |
| POST | `/command` | Execute a slash command against a department's agent session via `claude -p --continue`. |

---

## Messaging

| Method | Path | Description |
|--------|------|-------------|
| POST | `/cmail/send` | Send cmail to one or more departments; supports invoke, thread replies, group sends, and session targeting. |
| GET | `/cmail/threads` | List all threads (cmail and dispatch) merged and sorted by last activity. |
| GET | `/cmail/threads/{thread_id}` | Return full thread detail with all messages. |
| GET | `/cmail/messages` | Query individual cmail messages with filters for recipient, sender, search text, and unread status. |
| GET | `/cmail/stats` | Return aggregate cmail statistics: total count, thread count, unread count, and breakdown by sender. |
| GET | `/cmail/departments` | List available departments from company.yaml with aliases, voices, and descriptions. |

---

## Voice / TTS

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/tts` | TTS proxy: tries local Oasis Kokoro server first with retry, falls back to HF cloud; returns WAV audio. |
| POST | `/api/tts/stream` | Streaming TTS proxy: passes chunked WAV from Oasis directly; falls back to non-streaming on failure. |

---

## Events / Real-Time

| Method | Path | Description |
|--------|------|-------------|
| POST | `/events/emit` | Record an event to events.db and fan out to all active SSE subscribers. |
| GET | `/events/stream` | SSE real-time event stream with Last-Event-ID reconnect support and 15-second heartbeat. |
| GET | `/events/status` | Orchestrator diagnostics: event counts, SSE subscriber count, and breakdowns. |
| GET | `/feed` | Unified chronological event timeline with filters for type, dept, thread, and since timestamp. |

---

## Observability

| Method | Path | Description |
|--------|------|-------------|
| GET | `/dispatch/history` | Query dispatch voice message history with filters for sender, search, priority, and since timestamp. |
| GET | `/dispatch/stats` | Dispatch statistics: total, success, failed, today's count, and breakdown by sender. |
| GET | `/pulse/channels` | List all Pulse broadcast channels with post counts. |
| GET | `/pulse/channel/{channel_name}` | Read posts from a specific channel; supports time, limit, and offset filters. |
| GET | `/pulse/feed` | Merged feed across all channels with dept and tag filters. |
| POST | `/pulse/post` | Create a new Pulse post (max 500 characters, comma-separated tags). |
| GET | `/logs/` | List all whitelisted log files with metadata. |
| GET | `/logs/{name}` | Tail a specific log file by slug (e.g., hook-router, cmail-hooks); returns parsed entries. |
| GET | `/traces` | List recent traces grouped by trace_id with service and status filters. |
| GET | `/traces/services` | Per-service breakdown of span counts, errors, and operations. |
| GET | `/traces/live` | Flat stream of recent spans with service filter and cursor pagination. |
| GET | `/traces/{trace_id}` | Full waterfall view of a single trace with parent-child tree. |

---

## Topology / Graph

### Static Topology

| Method | Path | Description |
|--------|------|-------------|
| GET | `/topology/summary` | Overview: node/edge counts, layers, channels, types, and known issues. |
| GET | `/topology/nodes` | List all nodes; supports optional layer and type filters. |
| GET | `/topology/nodes/{node_id}` | Single node with outgoing/incoming edges and neighbor IDs. |
| GET | `/topology/path` | BFS shortest path between two nodes (directed or undirected). |
| GET | `/topology/impact/{node_id}` | Cascade failure simulation: failed/degraded nodes, broken edges, and narrative. |
| POST | `/topology/reload` | Force-reload graph cache from static JSON. |

### Sandbox (Mutable Working Graph)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/sandbox/graph` | Current in-memory working graph with live discovered edges. |
| GET | `/sandbox/nodes` | List nodes in working graph; supports layer and type filters. |
| GET | `/sandbox/node/{node_id}` | Single node with edges from working graph. |
| GET | `/sandbox/edges` | List edges in working graph; supports channel and type filters. |
| POST | `/sandbox/node` | Add a node to the working graph. |
| PATCH | `/sandbox/node/{node_id}` | Update fields on an existing node. |
| DELETE | `/sandbox/node/{node_id}` | Remove a node and all connected edges. |
| POST | `/sandbox/edge` | Add an edge between two nodes. |
| PATCH | `/sandbox/edge/{edge_id}` | Update fields on an existing edge. |
| DELETE | `/sandbox/edge/{edge_id}` | Remove an edge. |
| POST | `/sandbox/save` | Save working graph as a named snapshot; optionally promote to live. |
| POST | `/sandbox/reset` | Reset working graph to source and discard all mutations. |
| GET | `/sandbox/status` | Sandbox status: loaded state, node/edge counts, and unsaved mutation count. |

### Live Graph

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/graph/live` | Live topology graph derived from tracer spans (raw format). |
| GET | `/sandbox/graph/live` | Live topology in Sandbox-compatible format for ELK layout. |

---

## Snapshots

| Method | Path | Description |
|--------|------|-------------|
| GET | `/snapshots` | List all graph snapshots (metadata only), newest first. |
| GET | `/snapshots/live` | Get the currently promoted live snapshot with full graph JSON. |
| GET | `/snapshots/{snapshot_id}` | Get a specific snapshot with full graph JSON. |
| POST | `/snapshots` | Create a new graph snapshot; optionally promote to live. |
| PUT | `/snapshots/{snapshot_id}` | Update snapshot name and/or graph JSON. |
| DELETE | `/snapshots/{snapshot_id}` | Delete a snapshot; cannot delete the live snapshot — demote it first. |
| POST | `/snapshots/{snapshot_id}/promote` | Atomically promote a snapshot to live. |

---

## Utility

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check: service status, version, files directory, and staged file count. |
| GET | `/files/{file_id}/{filename}` | Download a staged file by UUID and filename. |
| POST | `/stage` | Stage a file for mobile download; returns a download URL. |
| POST | `/upload` | Upload a file from the Dispatch app into a department's cmail inbox. |
| GET | `/files` | List all staged files with metadata. |
| GET | `/config/voice-map` | Current department-to-Kokoro-voice mapping and list of available voices. |
| PUT | `/config/voice-map` | Update a department's voice assignment. |
| GET | `/config/anthropic-auth` | Serve Anthropic OAuth credentials from BWS with local file fallback. |
| POST | `/diagnostics/error-event` | Receive crash/error events from the Android app; stores to JSONL and forwards to engineering. |
| GET | `/diagnostics/crashes` | List recent crash events from the JSONL log. |
| GET | `/whiteboard` | Serve the current whiteboard (board.json) with task list and metadata. |

---

## Gemini

| Method | Path | Description |
|--------|------|-------------|
| POST | `/gemini/send` | Send a message to the native Gemini ACP worker; streams the response as SSE. |
| GET | `/gemini/sessions` | List native Gemini session files. |
| GET | `/gemini/sessions/{session_id}` | Get the content of a specific Gemini session. |

---

*Total: 75 endpoints*
