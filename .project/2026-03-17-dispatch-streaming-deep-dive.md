# Dispatch Streaming Communication — Deep Dive

**Date:** 2026-03-17
**Requested by:** Nigel (direct)
**Purpose:** How to make Dispatch feel like a phone call / WhatsApp / real-time communication bridge
**Reference:** Claude Code stream-json mode + Dispatch Android app codebase
**Confidence:** High — based on codebase analysis + external research

---

## TL;DR

**You're closer than you think.** About 70% of the plumbing already exists.

- The Android app already handles `agent_message_chunk` SSE events and has `MessageChunk` / `emitLiveChunk()` wired up
- `ChatBubbleRepository` + Room persistence + SSE insertion is fully built
- `chat_watcher.py` on File Bridge already polls JSONL files and pushes real-time bubbles via SSE
- The bubble types (nigel, agent, dispatch, tool) already model a full conversation

**What's missing:**
1. A streaming relay endpoint on File Bridge that connects Claude's stream-json output to SSE
2. Chunked TTS — send sentences to Kokoro as they complete, play audio progressively
3. Speech-to-text on the phone for voice input (the "phone call" feel)
4. A unified conversation screen that ties it all together

**The killer upgrade:** Stream-json mode gives you token-by-token text output. Combined with sentence-level TTS chunking, the agent starts *talking back* within ~300ms of generating its first sentence — instead of waiting for the entire response to finish.

---

## Table of Contents

1. [What Already Exists](#1-what-already-exists)
2. [The Streaming Architecture](#2-the-streaming-architecture)
3. [Server Side: File Bridge Streaming Relay](#3-server-side-file-bridge-streaming-relay)
4. [Android Side: Real-Time UI](#4-android-side-real-time-ui)
5. [Voice Streaming: The Phone Call Feel](#5-voice-streaming-the-phone-call-feel)
6. [Speech-to-Text: Voice Input](#6-speech-to-text-voice-input)
7. [Stream-JSON Reference](#7-stream-json-reference)
8. [Implementation Phases](#8-implementation-phases)
9. [What Could Go Better Than WhatsApp](#9-what-could-go-better-than-whatsapp)
10. [Risks and Gotchas](#10-risks-and-gotchas)
11. [Sources](#11-sources)

---

## 1. What Already Exists

This is the surprising part. The codebase audit revealed infrastructure that's already streaming-aware.

### Android Side — Already Built

| Component | File | What It Does |
|-----------|------|-------------|
| `MessageChunk` | `data/MessageRepository.kt` | Data class: `threadId`, `text`, `type` (agent_message_chunk / agent_thought_chunk) |
| `emitLiveChunk()` | `data/MessageRepository.kt` | Pushes chunks through `MutableSharedFlow<MessageChunk>(extraBufferCapacity=64)` |
| `liveChunks: SharedFlow` | `data/MessageRepository.kt` | Observable stream for UI — ViewModels can collect this |
| `ChatBubbleRepository` | `data/ChatBubbleRepository.kt` | Full Room-backed repo with SSE insertion, tail loading, scroll-up pagination |
| `ChatBubbleEntity` | `data/ChatBubbleEntity.kt` | Room entity with types: `nigel`, `agent`, `dispatch`, `tool` |
| `insertFromSse()` | `data/ChatBubbleRepository.kt` | Direct SSE → Room insertion. No polling needed. |
| `EventStreamClient` | `network/EventStreamClient.kt` | Handles `chat_bubble`, `agent_message_chunk`, `agent_thought_chunk` SSE event types |
| `SseConnectionService` | `network/SseConnectionService.kt` | Lifecycle-aware SSE client (connects on foreground, disconnects on background) |
| `AudioStreamClient` | `network/AudioStreamClient.kt` | Already plays PCM in 4800-byte chunks (~100ms each) via AudioTrack |

### Server Side (File Bridge) — Already Built

| Component | File | What It Does |
|-----------|------|-------------|
| `chat_watcher.py` | `file_bridge/chat_watcher.py` | Polls JSONL files every 500ms, parses new bytes into bubbles, pushes via SSE |
| `_push_sse_bubble()` | `file_bridge/chat_watcher.py` | Fan-out to all SSE subscribers via `asyncio.Queue` |
| `jsonl_reader.py` | `file_bridge/jsonl_reader.py` | Reads JSONL session files, parses into ChatBubble format |
| SSE endpoint | `routers/events.py` | `GET /events/stream` with `Last-Event-ID` reconnection, 15s heartbeat |
| Event emit | `routers/events.py` | `POST /events/emit` with fan-out to subscribers |

### What the Chat Watcher Already Does

The `chat_watcher.py` already converts JSONL records into conversation bubbles in real-time:

- **User records** → `nigel` bubbles (Nigel's typed input)
- **Assistant text blocks** → `agent` bubbles (Claude's response text, code blocks stripped)
- **`dispatch send` in Bash tool use** → `dispatch` bubbles (voice messages the agent sends)
- **Other tool use** → `tool` bubbles (tool name shown)

This is basically a conversation view already. It just needs to go faster (token-level instead of record-level) and get a dedicated UI.

---

## 2. The Streaming Architecture

### Current Flow (Batch — How It Works Today)

```
Nigel types message
  → App POST /cmail/send (blocks up to 60s)
  → File Bridge runs cmail send -i (subprocess)
  → Agent processes, writes JSONL record
  → chat_watcher.py detects change (500ms poll)
  → Parses complete record into bubbles
  → SSE push → Android EventStreamClient
  → ChatBubble inserted to Room DB
  → UI updates
```

**Latency:** Agent response time + 500ms poll + SSE delivery = seconds to minutes

### Proposed Flow (Streaming — The Goal)

```
Nigel speaks or types
  → App POST /chat/stream (SSE response, non-blocking)
  → File Bridge spawns claude -p --output-format stream-json
  → Each NDJSON line parsed in real-time
  → text_delta tokens → SSE "token" events → Android
  → UI: text appears word by word in chat bubble
  → Sentence boundary detected → Kokoro TTS (chunk)
  → Audio starts playing while next sentence generates
  → tool_use events → SSE "tool_status" → Android shows "Reading file..."
  → result event → SSE "done" → bubble finalized
```

**Latency:** First token in ~1-2s (API latency), first audio in ~300ms after first sentence completes

### The Three Streaming Channels

| Channel | Direction | Content | Transport |
|---------|-----------|---------|-----------|
| **Text stream** | Server → Phone | Token-by-token response text | SSE |
| **Audio stream** | Server → Phone | Sentence-level TTS chunks | Chunked HTTP or SSE binary |
| **Voice input** | Phone → Server | Speech-to-text transcription | HTTP POST |

---

## 3. Server Side: File Bridge Streaming Relay

### Option A: Claude Agent SDK (Recommended)

The Claude Agent SDK supports `include_partial_messages=True` which emits `StreamEvent` objects with raw API events including `content_block_delta` (text tokens) and `content_block_start/stop` (tool use boundaries).

**Event flow from the SDK:**
```
message_start → content_block_start → content_block_delta (text tokens) → content_block_stop
→ content_block_start (tool_use) → input_json_delta → content_block_stop
→ message_delta → message_stop → AssistantMessage → ResultMessage
```

**New File Bridge endpoint pattern:**
```
POST /chat/stream
  Body: { department, message, session_id? }
  Response: EventSourceResponse (SSE)
  Events:
    - event: token     data: {"text": "Hello"}
    - event: tool      data: {"name": "Read", "status": "started"}
    - event: tool      data: {"name": "Read", "status": "completed"}
    - event: done      data: {"session_id": "...", "thread_id": "..."}
```

**Key limitation:** Extended thinking (`max_thinking_tokens`) disables `StreamEvent` emission entirely. Don't enable it for streaming sessions.

### Option B: Subprocess with stream-json (Simpler)

Spawn `claude -p --output-format stream-json --verbose` as a subprocess, read stdout line by line, re-emit as SSE.

**Key stream-json event types to relay:**
- `assistant` — Full assistant message (complete response blocks)
- `stream_event` — Raw API events including `content_block_delta` (the tokens)
- `tool_progress` — Tool execution progress with tool name
- `result` — Final result with cost, usage, duration

**Subprocess pattern:**
```
asyncio.create_subprocess_exec(
    "claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
    stdout=asyncio.subprocess.PIPE
)
async for line in process.stdout:
    event = json.loads(line)
    # Parse and re-emit as SSE
```

**Tradeoff:** Option A (SDK) is cleaner but requires the Agent SDK installed on pop-os. Option B (subprocess) works with what you already have but means parsing raw NDJSON.

### Sentence Boundary Detection (For TTS Chunking)

The server should buffer incoming text tokens and emit a separate `sentence` event when a sentence boundary is detected:

**Boundary detection:** Period + space, question mark + space, exclamation + space, newline + newline (paragraph break). Don't split on commas or semicolons — those sound unnatural when TTS'd separately.

**Buffer strategy:**
- Accumulate tokens into a buffer string
- On each token, check if buffer ends with sentence boundary
- If yes: emit `event: sentence  data: {"text": "The buffered sentence."}` and clear buffer
- On `done`: flush any remaining buffer as a final sentence

---

## 4. Android Side: Real-Time UI

### The Conversation Screen

This needs to be a dedicated screen — not the existing Messages tab or Thread detail. Think Google Messages or WhatsApp:

- Chat bubbles: Nigel on the right (blue/primary), Agent on the left (surface variant)
- Text streams in word-by-word as `agent_message_chunk` events arrive
- Tool use shows as a status bar: "Reading customer-data.json..."
- Dispatch (voice) messages show as audio bubbles with play button
- Voice input button (microphone) at bottom alongside text compose

### Token-by-Token Rendering

**Already wired:** `EventStreamClient` handles `agent_message_chunk` → `messageRepository.emitLiveChunk(MessageChunk(...))`. The ViewModel collects `liveChunks` SharedFlow.

**Compose pattern for growing text:**
- ViewModel holds `StateFlow<String>` for current streaming bubble text
- Each `MessageChunk` appends to the accumulated string
- Compose `Text()` composable observes the StateFlow
- Only the Text node recomposes — not the whole chat list (Compose is smart about this)

**Word-level buffering:** Raw tokens arrive mid-word (e.g., "Hel" then "lo"). Buffer until whitespace or punctuation, then append the full word. This prevents the jittery mid-word rendering. GetStream's `StreamingText` composable does this with a 30ms delay per word.

### Smooth Auto-Scroll

Use `LazyListState.animateScrollToItem()` triggered by `LaunchedEffect` on token count. Not `scrollToItem()` (janky). The key: only auto-scroll if the user is already at the bottom — if they've scrolled up to read history, don't yank them down.

### Tool Use Status

Map tool names to human-readable status:
- `Bash` → "Running a command..."
- `Read` → "Reading files..."
- `Grep` → "Searching code..."
- `Write` → "Writing code..."
- `WebSearch` → "Searching the web..."
- `Agent` → "Delegating to a subagent..."

Show as a subtle animated bar between the last bubble and the compose field. Disappear when `content_block_stop` fires.

### Markdown Rendering

**During streaming:** Render as plain text. Full markdown re-parse on every token is expensive and causes visual flicker.
**On completion:** Switch to full markdown rendered view. Libraries: `compose-markdown` (Mikepenz) or `markwon`.

### GetStream's Library (Consider)

`io.getstream:stream-chat-android-ai` on Maven Central provides:
- `StreamingText` composable — progressive word-by-word reveal (30ms default per word)
- `AITypingIndicator` — animated dots with customizable label
- `ChatComposer` — full input bar with voice button

This could save weeks of UI work. Evaluate whether it can be used standalone without Stream's backend.

---

## 5. Voice Streaming: The Phone Call Feel

This is where it goes from "chat app" to "phone call."

### Current TTS Flow (Batch)

```
Agent finishes entire response
  → dispatch send "complete message"
  → FCM → Phone
  → Full message → Kokoro TTS (one request)
  → Wait for entire WAV
  → Play audio
```

**Problem:** Long responses mean long silence before any audio plays.

### Proposed TTS Flow (Streaming)

```
Agent generates text token by token
  → Sentence boundary detected
  → Sentence chunk → Kokoro TTS (immediate)
  → Chunk 1 audio plays while Chunk 2 is being generated/synthesized
  → Chunk 2 audio queues behind Chunk 1
  → Continuous speech with minimal gaps
```

**This is the money move.** Instead of waiting 30 seconds for a complete response and then 5 seconds for TTS, the agent starts "talking" within ~1-2 seconds of generating its first sentence.

### Kokoro TTS Streaming Capabilities

**Good news: Kokoro supports streaming mode.** Key facts:
- Standard mode: 500 char limit per request
- Streaming mode: 5000 char limit
- ~80ms TTFB on RTX-class GPU (your Oasis server)
- LiveKit integration exists (`livekit-kokoro`) wrapping Kokoro with OpenAI-compatible endpoints

**Sentence-level chunking is the right approach.** Don't try to TTS individual tokens — they're too short for natural speech. Buffer to sentence boundaries, fire TTS per sentence.

### The Audio Pipeline

The existing `AudioStreamClient` already:
- Uses a single-thread download executor (preserves FIFO order)
- Uses a single-thread playback executor (prevents AudioTrack overlap)
- Plays PCM in 4800-byte chunks via AudioTrack

**What changes:** Instead of one big WAV download per message, you queue multiple sentence-level WAV downloads. The serial executor architecture already handles this — sentence 1 downloads and plays while sentence 2 downloads, then sentence 2 plays immediately after.

**Estimated latency breakdown:**
- First token from Claude: ~1-2s
- First sentence complete: ~2-4s (depends on sentence length)
- Kokoro TTS for first sentence: ~80-200ms
- Download audio chunk: ~50-100ms (Tailscale local)
- **Total time to first audio: ~2-4 seconds** (vs current 30+ seconds for a long response)

### Voice Continuity

The gap between sentence audio chunks needs to be minimal. Options:
- **Pre-fetch:** Start TTS for sentence N+1 while sentence N is playing
- **Overlap buffering:** Queue sentence N+1's audio on the playback executor so it starts the instant N finishes
- **Concatenation:** On the server, concatenate sentence audio chunks into a growing stream (harder but seamless)

The existing serial executor pattern handles this naturally — sentence 2's audio is queued on the playback executor while sentence 1 is still playing. When sentence 1 finishes, sentence 2 starts immediately.

---

## 6. Speech-to-Text: Voice Input

For the true "phone call" feel, Nigel should be able to talk to the agents, not just type.

### Option A: Android SpeechRecognizer (Simplest)

- Built into Android
- `SpeechRecognizer.createSpeechRecognizer(context)`
- Returns text via `RecognitionListener.onResults()`
- **Pros:** No setup, good accuracy, supports partial results
- **Cons:** Requires internet (Google servers), slight latency, privacy concerns

### Option B: On-Device STT — Moonshine (Better)

- `openai/moonshine` — lightweight STT model designed to pair with Kokoro
- Runs on-device, no internet required
- **Pros:** Private, works offline, low latency
- **Cons:** Needs model integration (sherpa-onnx could host it, same as Piper)

### Option C: Whisper via Server (Most Accurate)

- Record audio on phone → POST to File Bridge → Whisper on pop-os GPU → return text
- **Pros:** Best accuracy, GPU acceleration
- **Cons:** Requires upload, network dependent

### Recommendation

Start with **Option A** (Android SpeechRecognizer). It's built-in, works today, and accuracy is good enough. Move to on-device Moonshine later for the offline/privacy benefits.

**UI pattern:** Hold microphone button → record → release → transcribe → auto-send. Like WhatsApp voice messages but converted to text before sending to the agent.

---

## 7. Stream-JSON Reference

Key event types from Claude Code's stream-json NDJSON output (21 total, these are the ones relevant to streaming):

### For Text Streaming
- **`stream_event`** — Raw API events. Filter for `content_block_delta` where `delta.type == "text_delta"`. The `delta.text` field contains the actual token text.
- **`streamlined_text`** — Simplified text content with thinking/tool_use blocks removed. Easier to use than raw `stream_event`.
- **`assistant`** — Complete assistant message with all content blocks. Emitted when the turn finishes.

### For Tool Status
- **`tool_progress`** — Tool execution progress with elapsed time, tool name, `parent_tool_use_id`
- **`stream_event`** with `content_block_start` where type is `tool_use` — tells you which tool started
- **`stream_event`** with `content_block_stop` — tool call complete

### For Session State
- **`system/init`** — Session initialized (version, model, tools list)
- **`system/status`** — Status changes
- **`system/task_started`** — Subagent spawned
- **`result`** — Final result with cost, usage, duration, stop_reason

### Required Flags
```bash
claude -p "prompt" --output-format stream-json --verbose
```
- `--verbose` is **mandatory** with stream-json (exits with error without it)
- `--include-partial-messages` enables token-by-token content chunks
- `--input-format stream-json` enables bidirectional streaming

---

## 8. Implementation Phases

### Phase 1: Text Streaming (Foundation)

**Server (File Bridge):**
- New endpoint: `POST /chat/stream` returning `EventSourceResponse`
- Spawns `claude -p --output-format stream-json --verbose --include-partial-messages`
- Reads NDJSON stdout line by line
- Emits SSE events: `token` (text delta), `tool` (tool status), `sentence` (complete sentence for TTS), `done`
- Non-blocking — returns SSE immediately, agent runs in background

**Android:**
- New screen: `ConversationScreen` with chat bubble layout
- Connect to `/chat/stream` via OkHttp SSE
- Collect tokens into ViewModel `StateFlow<String>` for live bubble text
- On `done`, finalize bubble and insert to ChatBubble Room DB
- Text compose field at bottom

**Result:** Text appears word-by-word as the agent generates it. Like watching someone type in real-time.

### Phase 2: Streaming TTS (The Voice)

**Server:**
- Sentence boundary detection in the streaming relay
- `sentence` SSE events emitted when each sentence completes

**Android:**
- On each `sentence` event: queue TTS request to Kokoro (existing AudioStreamClient)
- Serial download + serial playback executors handle ordering (already built)
- Agent starts "talking" after first sentence generates (~2-4s)

**Result:** The agent speaks to Nigel in real-time. Sentences play one after another with minimal gaps. No more waiting for the complete response.

### Phase 3: Voice Input (The Conversation)

**Android:**
- Microphone button on ConversationScreen
- Hold to record → Android SpeechRecognizer → text
- Auto-sends transcribed text to `/chat/stream`
- Shows Nigel's spoken text as a `nigel` bubble

**Result:** Nigel speaks, the agent responds in voice. It's a conversation.

### Phase 4: Polish (The Experience)

- Interrupt support: Nigel taps a button or speaks to interrupt the agent mid-response
- Tool status animations in the chat
- Markdown rendering on completed messages
- Session history (scroll up to see past conversations)
- Earbud integration: double-tap to start/stop voice, media buttons for control

---

## 9. What Could Go Better Than WhatsApp

WhatsApp is a messaging app. This could be something more — an **AI communication bridge** with capabilities no chat app has:

### Real-Time Tool Visibility
WhatsApp shows "typing..." and that's it. Dispatch can show exactly what the agent is doing: "Reading 3 files...", "Searching codebase for API endpoints...", "Writing deployment script...". Nigel sees the work happening, not just the result.

### Voice-First, Text-When-Needed
WhatsApp requires you to look at your phone. Dispatch already speaks through earbuds. The streaming upgrade means the agent starts talking within seconds, not after a long silence. Nigel can drive and have a conversation without ever looking at the screen.

### Multi-Agent Routing
WhatsApp has contacts. Dispatch has departments. Nigel can say "Ask Engineering about the build status" and the conversation routes to the right agent. The department picker already exists — voice commands could replace it.

### Session Awareness
WhatsApp doesn't know what the other person is doing. Dispatch already tracks agent sessions, context windows, tool use. The conversation screen could show: "Engineering is at 73% context, working on the cmail refactor." No other communication app has this.

### Proactive Communication
WhatsApp waits for messages. Dispatch agents already send proactive updates via `dispatch send`. The streaming architecture means those updates arrive in the conversation naturally — not as separate notifications.

---

## 10. Risks and Gotchas

### Extended Thinking Kills Streaming
If `max_thinking_tokens` is enabled on the Claude model, `StreamEvent` emission is disabled entirely. You get complete messages only. **Do not enable extended thinking for streaming sessions.**

### The 60-Second cmail/send Block
The current `/cmail/send` endpoint blocks the HTTP request while the agent runs (up to 60s timeout). The new `/chat/stream` endpoint must be fire-and-forget on the HTTP side, with SSE delivering the response. These are two different endpoints — don't try to make `/cmail/send` stream.

### Tailscale Latency
First connection through Tailscale has 2-5s cold-start. The existing retry logic in AudioStreamClient handles this, but for streaming you want the SSE connection pre-established (the EventStreamClient already does this — connects on app foreground).

### Token-Level Recomposition in Compose
Every token updates the StateFlow, which triggers Compose recomposition. This is fine if scoped correctly (only the Text composable recomposes, not the whole LazyColumn). Test on the Pixel 9 with long responses to verify no jank.

### Audio Gap Between Sentences
If sentence N finishes playing before sentence N+1's audio is ready, there's a gap. Mitigation: start TTS for sentence N+1 as soon as the text is ready (while N is still playing). The serial download executor already handles this — sentence 2's download starts while sentence 1 is playing.

### Kokoro 500-Character Limit
Standard mode has a 500-char limit. Most sentences are under this, but long ones need to be split. Streaming mode raises it to 5000 chars. Verify your Oasis Docker container supports streaming mode.

### Mobile Network SSE Reliability
SSE connections can drop on mobile networks. The existing `EventStreamClient` auto-reconnects with `Last-Event-ID` support. For conversation-critical streaming, the server should buffer the last N events for replay.

---

## 11. Sources

### Claude Code / Agent SDK
- [Claude Agent SDK — Stream Responses in Real-Time](https://platform.claude.com/docs/en/agent-sdk/streaming-output)
- Stream-json mode reference: `/home/xoom000/ClaudeCode/reference/tools/stream-json-mode.md`

### FastAPI Streaming
- [FastAPI Server-Sent Events (SSE) Official Docs](https://fastapi.tiangolo.com/tutorial/server-sent-events/)
- [FastAPI SSE for LLM Tokens (Medium)](https://medium.com/@hadiyolworld007/fastapi-sse-for-llm-tokens-smooth-streaming-without-websockets-001ead4b5e53)
- [Async Streaming Responses in FastAPI (dasroot.net)](https://dasroot.net/posts/2026/03/async-streaming-responses-fastapi-comprehensive-guide/)

### Android UI
- [GetStream stream-chat-android-ai (GitHub)](https://github.com/GetStream/stream-chat-android-ai)
- [GetStream AI Integrations — Android Chat Docs](https://getstream.io/chat/docs/sdk/android/ai-integrations/overview/)
- [GetStream WebRTC + Jetpack Compose](https://getstream.io/resources/projects/webrtc/platforms/android-compose/)

### TTS / Voice
- [livekit-kokoro — Kokoro TTS with LiveKit](https://github.com/taresh18/livekit-kokoro)
- [Real-Time LLM Voice Chat — Kokoro + Moonshine](https://medium.com/@princekrampah/real-time-llm-voice-chat-in-python-kokoro-moonshine-open-source-models-6c6270cbe967)
- [Kokoro TTS Overview (AI Center)](https://aicenter.ai/products/kokoro-tts)
- [Kokoro TTS (Hugging Face)](https://huggingface.co/spaces/hexgrad/Kokoro-TTS)

### Codebase References
- Chat watcher: `tools/file-bridge/src/file_bridge/chat_watcher.py`
- JSONL reader: `tools/file-bridge/src/file_bridge/jsonl_reader.py`
- SSE events: `tools/file-bridge/src/file_bridge/routers/events.py`
- Android MessageRepository: `Dispatch/app/src/main/java/dev/digitalgnosis/dispatch/data/MessageRepository.kt`
- Android ChatBubbleRepository: `Dispatch/app/src/main/java/dev/digitalgnosis/dispatch/data/ChatBubbleRepository.kt`
- Android EventStreamClient: `Dispatch/app/src/main/java/dev/digitalgnosis/dispatch/network/EventStreamClient.kt`
- Android AudioStreamClient: `Dispatch/app/src/main/java/dev/digitalgnosis/dispatch/network/AudioStreamClient.kt`
