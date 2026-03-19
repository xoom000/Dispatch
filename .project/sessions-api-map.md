# Claude Code Sessions API — Complete Endpoint Map

**Compiled:** 2026-03-17
**Source:** Bug reports, source analysis, official docs, deep dives
**Base URL:** `https://api.anthropic.com`
**Auth:** OAuth Bearer token (via `claude /login`)

---

## WHAT THIS IS

This maps the **Sessions API** — the system that powers:
- **Claude Code on the Web** (cloud sessions at claude.ai/code)
- **Remote Control** (local session → phone/browser bridge)
- **Teleportation** (web session → local terminal)
- **Session management** (continue, resume, fork)

This is NOT the Messages API or the chat interface API. This is the infrastructure that lets sessions move between cloud, CLI, and mobile.

---

## 1. CORE SESSIONS API

**Base:** `https://api.anthropic.com`

### POST /v1/sessions
**Purpose:** Create a new session (used by remote control bridge + web)
**Auth:** `Authorization: Bearer <oauth_token>`
**Beta Header:** `anthropic-beta: ccr-byoc-2025-07-29`
**Body:** Session configuration (model, environment, task)
**Response:** Session object with session ID
**Errors:**
- 503: Service unavailable (no retry in current CLI)
- Socket hang up: Intermittent failures

### GET /v1/sessions/{session_id}
**Purpose:** Get session details (used by teleport validation)
**Auth:** `Authorization: Bearer <oauth_token>`
**Headers:** `x-organization-uuid: {org_uuid}`
**Response:** Session data, status, metadata
**Errors:**
- 404: "Session not found: {session_id}" (teleport fails)
- 401: "Session expired..."
- 403: Authorization issue (falls through to generic error)

### GET /v1/session_ingress/session/{session_id}
**Purpose:** Get session data with loglines (alternative/internal endpoint)
**Auth:** `Authorization: Bearer <oauth_token>`
**Response:** Full session data including loglines
**Note:** This endpoint works when `/v1/sessions/{id}` returns 404 — was the root cause of the teleport bug fixed in v2.1.42

---

## 2. REMOTE CONTROL / BRIDGE API

The bridge is how your local CLI registers as a relay target.

### POST /v1/environments/bridge
**Purpose:** Register a bridge environment (local machine → Anthropic relay)
**Auth:** `Authorization: Bearer <oauth_token>`
**Response:** 200 with `environment_id` (format: `env_01ND5bJVuGgujp4CZZPTCUCW`)
**Feature Flag:** `tengu_ccr_bridge: true` (required)

### DELETE /v1/environments/{environment_id}
**Purpose:** Deregister bridge environment (cleanup on session end)
**Auth:** `Authorization: Bearer <oauth_token>`

### GET /work/poll
**Purpose:** Poll for incoming messages from remote clients (phone/browser)
**Auth:** `environment_secret` credential
**Response:** 200 with work payload OR 200 with no work
**Polling interval:** ~2-5 seconds
**Errors:**
- 401: "Authentication failed - Invalid OAuth token" (known bug: no token refresh retry)
**Known issue:** `pollForWork` lacks 401 retry/token-refresh logic that other bridge methods have

### POST /work/stop (inferred)
**Purpose:** Stop current work item
**Auth:** Bearer token
**Note:** Referenced in code as `stopWork` — uses retry wrapper

---

## 3. WEB SESSIONS API (claude.ai/code)

These endpoints are called by the claude.ai/code web interface.

### GET claude.ai/api/code/sessions
**Purpose:** List user's code sessions (web UI session list)
**Auth:** Cookie-based (`sessionKey`)
**Response:** List of active/archived sessions
**Note:** Returns 403 to non-browser clients (Cloudflare protection)

### Session URL Pattern
```
https://claude.ai/code?session={session_id}
```
**Session ID Format:** `session_01Mhb...` (prefixed, not plain UUID)

---

## 4. SESSION LIFECYCLE

### Creation Flow (Remote → Web)
```
CLI: claude --remote "Fix the auth bug"
  → POST /v1/sessions (create cloud session)
  → Returns session_id
  → Session runs on Anthropic-managed VM
  → Accessible at claude.ai/code?session={id}
```

### Creation Flow (Remote Control)
```
CLI: claude remote-control
  → POST /v1/environments/bridge (register local machine)
  → Returns environment_id + session URL + QR code
  → POST /v1/sessions (create session record)
  → GET /work/poll (begin polling loop, 2-5s interval)
  → Phone/browser connects to session URL
  → Relay routes messages bidirectionally
```

### Teleportation Flow (Web → Local)
```
CLI: claude --teleport <session_id>
  → GET /v1/sessions/{session_id} (validate session)
  → Verify: clean git state, correct repo, branch available, same account
  → git fetch + checkout session branch
  → Load conversation history from session
  → Session now running locally
```

### Resume Flow (Local → Local)
```
CLI: claude -r <session_id>
  → Read ~/.claude/projects/<encoded-cwd>/<session-id>.jsonl
  → Load conversation history
  → Continue from where session left off
```

---

## 5. SESSION STORAGE (Local)

### File Location
```
~/.claude/projects/<encoded-cwd>/<session-id>.jsonl
```
- `<encoded-cwd>`: Absolute path with non-alphanumeric chars → `-`
  - `/home/xoom000/osint` → `-home-xoom000-osint`

### Session Index
```
~/.claude/projects/<encoded-cwd>/sessions-index.json
```
- Quick lookup of available sessions
- **Known bug:** Only reads first line of JSONL — misses sessions where first line is `file-history-snapshot`

### JSONL File Structure
```jsonl
{"type":"file-history-snapshot", ...}
{"type":"system","sessionId":"004cc9d6-...","gitBranch":"claude/fix-xxx","slug":"sharded-sleeping-pebble","compactMetadata":{...}}
{"type":"user","sessionId":"004cc9d6-...","content":"Fix the auth bug"}
{"type":"assistant","sessionId":"004cc9d6-...","content":"I'll analyze the auth module..."}
```

### Message Types in JSONL
| Type | Description |
|------|-------------|
| `system` | Session metadata (sessionId, gitBranch, slug, compactMetadata) |
| `user` | User messages |
| `assistant` | Claude responses |
| `file-history-snapshot` | File state snapshot (from web auto-compaction) |

### Session ID Formats
| Context | Format | Example |
|---------|--------|---------|
| Local sessions | UUID v4 | `004cc9d6-a1b2-c3d4-e5f6-123456789abc` |
| Web/cloud sessions | Prefixed | `session_01Mhb...`, `session_01RyZ89nysBFFZnqFMZ4KpkZ` |
| Environment IDs | Prefixed | `env_01ND5bJVuGgujp4CZZPTCUCW` |

---

## 6. AUTHENTICATION

### OAuth Flow
```
claude /login → Browser OAuth at claude.ai → Token stored in OS keychain
```

### Token Types
| Token | Purpose | Lifetime |
|-------|---------|----------|
| OAuth bearer token | API authentication | Refresh-based |
| Session token | Scoped to session | Short-lived |
| Relay token | Bridge communication | Short-lived |
| Environment secret | Poll authentication | Short-lived |

**Key detail:** Each credential expires independently. Compromising one doesn't compromise others.

### Token Storage
- **macOS:** Keychain
- **Linux:** System keyring (libsecret)
- **Fallback:** File-based in `~/.claude/`

---

## 7. STREAMING / MESSAGE RELAY

### Transport
- **CLI → Anthropic:** HTTPS polling (outbound only, no inbound ports)
- **Phone/Browser → Anthropic:** HTTPS + SSE
- **Relay model:** Application messages only (prompts, tool results, chat events) — NOT raw network packets

### What flows through the relay
- Chat messages (user prompts, assistant responses)
- Tool calls and results
- Permission prompts
- Session state updates
- Diff/file change notifications

### What stays local
- Filesystem access
- MCP servers
- Tool execution
- Project configuration
- Git operations

### Connection Health
- 10-minute hard TTL if CLI loses connectivity
- Auto-reconnect with exponential backoff (1s → 30s max)
- No heartbeat feedback to phone (one-way health check)
- Append-only event log for conversation history sync

---

## 8. SDK SESSION MANAGEMENT

### Python SDK
```python
from claude_agent_sdk import query, ClaudeAgentOptions, ClaudeSDKClient

# Auto session management
async with ClaudeSDKClient(options=options) as client:
    await client.query("first prompt")    # Creates session
    await client.query("follow up")       # Auto-continues same session

# Manual resume by ID
async for msg in query(prompt="...", options=ClaudeAgentOptions(resume=session_id)):
    ...

# Fork session (branch history, new ID)
async for msg in query(prompt="...", options=ClaudeAgentOptions(resume=session_id, fork_session=True)):
    ...

# List sessions
from claude_agent_sdk import list_sessions, get_session_messages
sessions = list_sessions()
messages = get_session_messages(session_id)
```

### TypeScript SDK
```typescript
import { query, listSessions, getSessionMessages } from "@anthropic-ai/claude-agent-sdk";

// Continue most recent session
for await (const msg of query({ prompt: "...", options: { continue: true } })) { ... }

// Resume specific session
for await (const msg of query({ prompt: "...", options: { resume: sessionId } })) { ... }

// Fork session
for await (const msg of query({ prompt: "...", options: { resume: sessionId, forkSession: true } })) { ... }

// Disable persistence (in-memory only)
for await (const msg of query({ prompt: "...", options: { persistSession: false } })) { ... }
```

### Session Options
| Option | Description |
|--------|-------------|
| `continue` | Resume most recent session in current directory |
| `resume` | Resume specific session by ID |
| `fork_session` | Create new session branching from existing one |
| `session_id` | Use specific UUID for new session |
| `persistSession` | false = in-memory only (TypeScript) |
| `no_session_persistence` | Don't save to disk (CLI `--no-session-persistence`) |

---

## 9. CLI COMMANDS & FLAGS

### Session Commands
| Command/Flag | Description |
|-------------|-------------|
| `claude -c` | Continue most recent session |
| `claude -r <id\|name>` | Resume specific session |
| `claude --teleport` | Interactive picker for web sessions |
| `claude --teleport <session_id>` | Resume specific web session locally |
| `claude --remote "task"` | Create new web session from terminal |
| `claude remote-control` | Start remote control server |
| `claude --remote-control` / `--rc` | Interactive session + remote control |
| `claude --session-id <uuid>` | Use specific session ID |
| `claude --fork-session` | Fork when resuming (use with `-r` or `-c`) |
| `claude --name "name"` / `-n` | Name the session |
| `claude --no-session-persistence` | Don't persist session to disk |
| `claude --from-pr 123` | Resume sessions linked to a PR |

### In-Session Commands
| Command | Description |
|---------|-------------|
| `/teleport` or `/tp` | Interactive web session picker |
| `/tasks` | View background/web sessions |
| `/resume` | Resume a previous session |
| `/remote-control` or `/rc` | Enable remote control mid-session |
| `/remote-env` | Choose web environment |
| `/rename` | Rename current session |

---

## 10. KNOWN BUGS & EDGE CASES

| Issue | Detail |
|-------|--------|
| **Teleport 404** | CLI calls `/v1/sessions/{id}` but session only exists at `/v1/session_ingress/session/{id}`. Fixed in v2.1.42 |
| **Session index miss** | `sessions-index.json` only reads first JSONL line — misses 78% of teleported sessions if first line is `file-history-snapshot`. Issue #28039 |
| **Remote control 401** | `pollForWork` lacks token refresh retry logic — bridge dies on expired tokens. Issue #30102 |
| **Session creation 503** | `POST /v1/sessions` has no retry mechanism — one failure = dead session |
| **Web 403** | `claude.ai/api/code/sessions` returns 403 to non-browser clients (irrelevant to CLI, which uses `api.anthropic.com`) |

---

## 11. ENVIRONMENT VARIABLES

| Variable | Purpose |
|----------|---------|
| `CLAUDE_CODE_REMOTE` | Set to `true` in cloud environments |
| `CLAUDE_ENV_FILE` | Path to write persistent env vars from hooks |
| `CLAUDE_PROJECT_DIR` | Project root directory |
| `BASE_API_URL` | Override API base (default: `https://api.anthropic.com`) |

---

## SOURCES

- [Claude Code on the Web — Official Docs](https://code.claude.com/docs/en/claude-code-on-the-web)
- [Remote Control — Official Docs](https://code.claude.com/docs/en/remote-control)
- [CLI Reference — Official Docs](https://code.claude.com/docs/en/cli-reference)
- [Agent SDK Sessions — Official Docs](https://platform.claude.com/docs/en/agent-sdk/sessions)
- [Teleport 404 Bug — Issue #24106](https://github.com/anthropics/claude-code/issues/24106) — Revealed `/v1/sessions/{id}` and `/v1/session_ingress/session/{id}` endpoints
- [Session Index Bug — Issue #28039](https://github.com/anthropics/claude-code/issues/28039) — Revealed JSONL structure and index behavior
- [Remote Control 401 Bug — Issue #30102](https://github.com/anthropics/claude-code/issues/30102) — Revealed `/v1/environments/bridge`, `/v1/sessions`, `/work/poll` endpoints + source code line refs
- [Deep Dive: How Remote Control Works](https://dev.to/chwu1946/deep-dive-how-claude-code-remote-control-actually-works-50p6)
- [Session Teleportation on Habr](https://habr.com/en/articles/986590/)
