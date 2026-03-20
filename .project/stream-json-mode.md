# Stream-JSON Mode — Claude Code's Streaming Wire Protocol

**Source:** v2.1.71 snapshot (`dissected/cli-beautified.js`, 520,715 lines)
**Feature introduced:** v0.2.66 (CHANGELOG line 1207)
**Last verified:** 2026-03-17

---

## What It Is

Stream-JSON is a real-time NDJSON (newline-delimited JSON) output format for Claude Code's `--print` (non-interactive/headless) mode. Instead of waiting for the entire response to complete, it emits each event as a separate JSON object on its own line, as it happens.

This is the same protocol the SDK daemon uses internally to communicate with child Claude Code processes (line 468411).

---

## How To Use It

### Basic Usage
```
claude -p "your prompt" --output-format stream-json --verbose
```

### With Streaming Input (Bidirectional)
```
echo '{"type":"user","message":{"role":"user","content":"hello"}}' | claude -p --input-format stream-json --output-format stream-json --verbose
```

### All Three Output Formats (line 519165, `.choices()`)
- `text` — Default. Plain text, just the final result string.
- `json` — Single JSON object with the final result.
- `stream-json` — Real-time NDJSON, one event per line as they happen.

---

## Required Flags

**`--verbose` is mandatory with stream-json in print mode.**
Source: line 489106-489109 — exits with error if stream-json used without verbose.

**`--print` (`-p`) is required.**
Stream-json is only available in non-interactive/headless mode.

---

## Optional Flags

- `--include-partial-messages` — Includes incremental content chunks as they arrive (token-by-token). Requires `--print` AND `--output-format=stream-json` (line 519462).
- `--replay-user-messages` — Re-emits user messages from stdin back on stdout for acknowledgment. Requires BOTH input and output to be stream-json (line 519459).
- `--input-format stream-json` — Enables streaming JSON input. If input is stream-json, output MUST also be stream-json (line 519453).

---

## Environment Variable

`CLAUDE_CODE_INCLUDE_PARTIAL_MESSAGES` — Can enable partial messages via env var instead of the CLI flag (line 519238).

---

## Validation Rules (lines 519452-519462)

1. Input format can only be "text" or "stream-json" — anything else exits with error.
2. If input is stream-json, output MUST also be stream-json.
3. `--sdk-url` requires BOTH input and output to be stream-json.
4. `--replay-user-messages` requires BOTH to be stream-json.
5. `--include-partial-messages` requires --print AND --output-format=stream-json.
6. stream-json with --print requires --verbose.

---

## Event Types (21 total)

From the schema union `b_4` at line 226295:

### Core Message Events
1. **assistant** — Full assistant message with content blocks, parent_tool_use_id (line 226056)
2. **user** — User message (emitted when replay-user-messages is on)
3. **result** — Final result. Two variants:
   - `result/success` — Includes: `result` (text), `duration_ms`, `duration_api_ms`, `total_cost_usd`, `usage`, `modelUsage`, `permission_denials`, `structured_output`, `stop_reason` (line 226082)
   - `result/error_*` — Error subtypes: `error_during_execution`, `error_max_turns`, `error_max_budget_usd`, `error_max_structured_output_retries` (line 226099)

### Streaming Events
4. **stream_event** — Raw Anthropic API stream events (content_block_start, content_block_delta, message_start, etc.). Includes `parent_tool_use_id` (line 226140)
5. **streamlined_text** — Simplified text content from assistant message, thinking/tool_use blocks removed (line 226068)
6. **streamlined_tool_use_summary** — Cumulative summary string of tool calls, e.g. "Read 2 files, wrote 1 file" (line 226073)

### System Events
7. **system/init** — Initialization: version, model, cwd, tools list, MCP servers, permission mode, agents, slash commands, output style, plugins (line 226115)
8. **system/status** — Status changes with optional permission mode (line 226155)
9. **system/compact_boundary** — Context compaction happened. Includes trigger (manual/auto) and pre_tokens count (line 226146)
10. **system/local_command_output** — Output from slash commands like /voice, /cost (line 226162)

### Hook Events
11. **system/hook_started** — Hook execution started. Includes hook_id, hook_name, hook_event (line 226168)
12. **system/hook_progress** — Hook stdout/stderr during execution (line 226176)
13. **system/hook_response** — Hook completed. Includes exit_code, outcome (success/error/cancelled) (line 226187)

### Tool Events
14. **tool_progress** — Tool execution progress with elapsed time, tool name, parent_tool_use_id (line 226200)
15. **tool_use_summary** — Summary of preceding tool uses (line 226269)

### Task/Agent Events
16. **system/task_started** — Subagent task spawned. Includes task_id, description, task_type, prompt (line 226244)
17. **system/task_progress** — Task progress with usage stats (tokens, tool_uses, duration) (line 226255)
18. **system/task_notification** — Task completed/failed/stopped. Includes output_file, summary, usage (line 226230)

### Other Events
19. **rate_limit_event** — Rate limit info changes (line 226063)
20. **auth_status** — Authentication status (line 226209)
21. **system/elicitation_complete** — MCP elicitation complete (line 226275)
22. **system/files_persisted** — Files persisted with file_ids (line 226217)
23. **prompt_suggestion** — Predicted next user prompt (line 226282)

---

## How It Works Internally

### Output Writer — `Ve6` class (line 483847)
The `write()` method at line 483979 is dead simple:
```
process.stdout.write(JSON.stringify(event) + "\n")
```
One JSON object per line. NDJSON format.

### Input Reader — `Ve6.read()` (line 483874)
Reads stdin line by line, parses each line as JSON. Handles these input message types:
- `user` — New user messages
- `control_response` — Permission responses
- `control_request` — Control requests
- `keep_alive` — Ignored (heartbeat)
- `update_environment_variables` — Dynamically updates process.env
- `assistant` / `system` — Passed through

### Main Loop (line 489123-489127)
In stream-json mode with verbose, every event from the generator gets written directly to stdout. No buffering — each event streams the instant it's produced.

### SDK/Bridge Usage (line 468411)
When the SDK daemon spawns child Claude Code processes, it ALWAYS uses:
`--print --sdk-url <url> --session-id <id> --input-format stream-json --output-format stream-json --replay-user-messages`

The `--sdk-url` flag automatically sets both formats to stream-json and enables verbose and print mode (lines 519239-519243).

### Two Writer Classes
- **`Ve6`** (line 483847) — Standard stdio writer. Reads from stdin, writes to stdout.
- **`ku1`** (line 485386) — Extends Ve6 for SDK/remote connections via SSE transport. Used when `--sdk-url` is provided.

---

## Bidirectional Input Messages

When using `--input-format stream-json`, you can send these on stdin:

1. **User message** — `{"type":"user","message":{"role":"user","content":"your prompt"}}`
2. **Control response** — Respond to permission prompts: `{"type":"control_response","response":{"request_id":"...","subtype":"success","response":{...}}}`
3. **Keep alive** — `{"type":"keep_alive"}` (silently ignored, for heartbeat)
4. **Update env vars** — `{"type":"update_environment_variables","variables":{"KEY":"value"}}` (dynamically updates process.env mid-session!)

---

## DG Use Cases

1. **Real-time monitoring dashboards** — Consume NDJSON stream to show Claude's work in real time
2. **Custom UIs** — Build web interfaces that render each event as it arrives
3. **Programmatic orchestration** — Send inputs and receive outputs as structured data
4. **Hook observability** — See hook_started/progress/response events in real time
5. **Cost tracking** — Result event includes total_cost_usd and per-model usage breakdown
6. **Dynamic env injection** — Use update_environment_variables to change settings mid-session
7. **Automated permission handling** — Respond to control_requests programmatically
8. **Watchman integration** — Stream events to monitoring for health/performance tracking

---

## Gotchas

- `--verbose` is REQUIRED — without it, stream-json mode exits with error (line 489106)
- The `stream_event` type wraps raw Anthropic API events — they're nested under `.event`
- `control_request` and `control_response` are filtered OUT of the main event stream at line 489128 (they're handled separately for permission flow)
- `streamlined_text` and `streamlined_tool_use_summary` are also filtered from the result array (line 489128) — they're streaming-only events
- When `--json-schema` is used with stream-json, structured output validation is applied to the result (line 519472-519481)
