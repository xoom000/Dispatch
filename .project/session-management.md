---
title: "Session Management — CLI, Disk Layout, and Metadata"
domain: "architecture"
tags: ["sessions", "jsonl", "resume", "list-sessions", "session-metadata", "rename", "preview", "session-router", "cwd-hash", "clear", "session-id", "uuid", "regenerate"]
source_version: "2.1.71"
confidence: "confirmed"
created: "2026-03-14"
last_verified: "2026-03-15"
source_file: "cli-beautified.js"
source_lines: "457244-459039, 226287-226295, 418351, 422004-422005, 458846-458865, 391810-391857, 2034-2037"
---

# Session Management — CLI, Disk Layout, and Metadata

## What It Is

Claude Code persists conversations as JSONL files on disk. There is no SQLite index, no session database internal to Claude Code — just raw files. Session management happens through CLI flags, in-session slash commands, and the interactive `/resume` picker.

## Disk Layout

Confirmed against live filesystem (126 project directories as of 2026-03-14).

```
~/.claude/projects/
  {hashed_cwd}/                        # cwd path with / replaced by -
    {session_uuid}.jsonl               # main session file
    {session_uuid}/                    # subagent directory (only if subagents ran)
      subagents/
        agent-{agent_uuid}.jsonl       # subagent transcript (line 457324)
        agent-{agent_uuid}.meta.json   # subagent metadata (line 457328)
```

- **No `.meta.json` for main sessions** — metadata is embedded in the JSONL itself
- **No separate index** — Claude Code scans directories on demand
- **Session ID = filename** — strip `.jsonl` extension to get the UUID
- **Subagent filter** — the `agent-` prefix distinguishes subagent files from main sessions (line 458697)
- **CWD hash**: `bJ(DA())` calls `ID(cwd)` which maps the cwd to a filesystem-safe directory name (line 457371)

## JSONL Entry Types

Verified against a real session (3,613 entries in the current session):

| Type | Count | Contains |
|---|---|---|
| `progress` | 2851 | Tool calls, API progress events |
| `assistant` | 354 | Model responses |
| `user` | 326 | User messages |
| `system` | 45 | System events |
| `file-history-snapshot` | 39 | File state snapshots |
| `last-prompt` | 2 | Last prompt markers |
| `queue-operation` | 6 | Queue events |

Each `user` and `assistant` entry has: `parentUuid`, `uuid`, `timestamp`, `sessionId`, `isSidechain`, `type`, `message` (with `role` and `content`), plus `cwd`, `gitBranch`, `permissionMode`, `version` on user entries.

### Head vs Tail Convention

The enrichment function `Khz` at line 458846 reads:
- **Head of file**: `cwd`, `gitBranch`, `teamName`, `agentSetting`, `isSidechain`
- **Tail of file**: `customTitle`, `summary`, `tag`, `prNumber`, `prUrl`, `lastPrompt`

This means metadata is split between the first few entries (written at session start) and appended records at the end (written on session events like rename or close).

## Session Metadata Schema

The `_D_` Zod schema at line 226287 is described as "Session metadata returned by listSessions":

| Field | Type | Description |
|---|---|---|
| `sessionId` | string | UUID |
| `summary` | string | Display title: customTitle → auto-generated → firstPrompt |
| `lastModified` | number | Milliseconds since epoch (from file mtime) |
| `fileSize` | number | File size in bytes |
| `customTitle` | string? | User-set title via `/rename` |
| `firstPrompt` | string? | First meaningful user message |
| `gitBranch` | string? | Git branch at session end |
| `cwd` | string? | Working directory |

**Not in session metadata:** model used, context token counts, cost. Token data is in `progress` entries but not surfaced at the listing level.

## CLI Flags for Session Management

From CLI parser at line 519163:

- **`--resume [value]`** — Resumes by session ID, or opens interactive picker with optional search term. Documented as `claude -r <session_id>`.
- **`--continue` / `-c`** — Resumes most recent session in current directory.
- **`--fork-session`** — Creates a new session ID instead of reusing original (use with `--resume` or `--continue`).
- **`--session-id <uuid>`** — Use a specific UUID for a NEW conversation (not the same as resume). Error if combined with `--continue` or `--resume` without `--fork-session` (line 519249).
- **`--resume-session-at <message-id>`** — Hidden flag: resume but only replay messages up to a specific assistant message ID.
- **`--no-session-persistence`** — Disables JSONL writing entirely (only works with `--print`).

**No `--list-sessions` flag exists.** There is no parseable session enumeration via CLI.

## Session Listing Internals

The internal session loading pipeline:

1. **`lh1(limit)`** at line 458504 (`loadMessageLogs`) — primary entry point, calls `shq()` then `O$6()` to enrich
2. **`nE6(dir, limit, projectPath)`** at line 458975 — scans a single project directory via `qt6()` (readdirSync, filters `.jsonl`), returns "lite" session objects with only filesystem stats
3. **`zhz(session, buffer)`** at line 459000 — enriches lite sessions by reading JSONL head and tail via `Khz()`
4. **`St6(worktrees, limit)`** at line 458575 — loads sessions for `/resume` picker (same-repo scope)
5. **`mo8(limit)`** / **`gb1(limit)`** at line 458514 — loads sessions across ALL projects

For the session router, the filesystem scan pattern used internally is the right approach.

## SDK Functions

The `@anthropic-ai/claude-agent-sdk` (Node.js/Python) exposes:

- **`listSessions()`** — returns array of session metadata objects matching the `_D_` schema (line 478380/481101)
- **`getSessionMessages(sessionId, { limit, offset })`** — returns messages for a session with pagination (line 481110)

These are the cleanest API for programmatic access. Accessible from any Node.js or Python script that imports the SDK.

## Rename Mechanism

Two in-session-only mechanisms:
- **`/rename` slash command** (line 418351) — works inside an active session, checks it's not a swarm teammate before allowing
- **Ctrl+R in interactive picker** (line 422004) — triggers rename mode in the TUI, fires `tengu_session_rename_started`

When rename executes, it writes `customTitle` to the JSONL tail and updates `b$().currentSessionTitle` in memory (line 458111-458113). The event fires `tengu_session_renamed`.

**No external CLI rename.** To rename a session externally, you would need to append a JSON record with `customTitle` to the JSONL tail. The exact format needs verification before attempting — do not write blindly.

## Preview Mechanism

- **Ctrl+V in interactive picker** (line 422005) — opens preview mode with `messageCount`, fires `tengu_session_preview_opened`
- **No `--preview` CLI flag**

For external preview: parse the JSONL directly, filtering for `type: "user"` and `type: "assistant"` entries. Or use `getSessionMessages()` from the SDK.

## Building a Session Router

Recommended approach based on how Claude Code does it internally:

1. **Enumerate**: scan `~/.claude/projects/` for subdirectories, then scan each for `*.jsonl` files (exclude `agent-` prefix)
2. **Quick stats**: mtime → last activity, size → fileSize, filename → sessionId
3. **Metadata**: read head (~50 lines) for cwd/gitBranch, read tail (~20 lines) for customTitle/summary
4. **Messages**: stream-parse JSONL filtering for user/assistant types
5. **Target**: use `--resume <session_id>` for routing invokes to specific sessions

The project-locked sessions for the two-plane architecture (in `project_sessions` DB table) build ON TOP of this filesystem layer — Claude Code itself has no awareness of which sessions are "project sessions" vs interactive. That's entirely DG infrastructure.

## /clear — Context Reset and Session Identity

The `/clear` command (or `./clear` script) nukes the conversation context. But it also **regenerates both the session UUID and the conversation ID**. You are on a different session after clearing.

**Source:** `clearConversation` function (`Si8`) at line 391810 in v2.1.71 snapshot.

**What happens when /clear fires:**

1. Messages are wiped — `setMessages(() => [])` (line 391820)
2. A new conversation ID is generated — `setConversationId(randomUUID())` (line 391820)
3. Session caches are cleared — `clearSessionCaches()` and `clearSessionMetadata()` (line 391852)
4. **Session ID is regenerated** — `regenerateSessionId({ setCurrentAsParent: true })` (line 391852-391853)
5. Init hooks are re-fired — `await nC()` then processes any `clear` event hooks (line 391854-391856)

**The regeneration function** (`Zg1` at line 2034):
- Saves the current session ID to `parentSessionId` before overwriting
- Generates a fresh UUID via `crypto.randomUUID()`
- Clears `sessionProjectDir`
- Returns the new session ID

**What this means in practice:**
- After `/clear`, any external system tracking your session by UUID will lose you
- The `parentSessionId` linkage creates a breadcrumb trail — telemetry can follow the chain
- Hook payloads after `/clear` will carry the NEW session ID
- The JSONL file for the previous session remains on disk; a new JSONL file starts for the new UUID
- cmail `--session` targeting breaks after a `/clear` unless the session is re-registered

**Also reset during /clear:**
- All running tasks are killed and cleaned up (lines 391822-391833)
- Task state, file history, and MCP client state are wiped (lines 391834-391850)
- Attribution resets to fresh state

## Gotchas

- **No internal session index** — Claude Code always re-scans files. Fast because it only reads head/tail, but there's no O(1) lookup by session ID.
- **Subagent sessions are hidden** by the `agent-` prefix filter. They ARE accessible but need the parent session ID to construct the path.
- **`isSidechain` filter** — sessions where `isSidechain: true` are hidden from the `/resume` picker (line 459019). These are parallel conversation branches from the same session.
- **`teamName` filter** — sessions with a `teamName` set are also hidden from the picker (line 459020). Team Mode sessions don't appear in regular `/resume`.
- **`$CLAUDE_SESSION_ID` is NOT an env var** — it only works as a template substitution inside skill bodies (line 222701). Session ID for external scripts must come from the hook payload or be written to a file.

## Cross-References

- `reference/dispatch-ide/TWO-PLANE-SOURCE-VERIFICATION.md` — session ID capture mechanism for project registration
- `reference/hooks/all-hook-events.md` — hook payload includes `session_id` and `transcript_path`
- `reference/version-tracking/source-snapshots.md` — snapshot version for these line numbers
