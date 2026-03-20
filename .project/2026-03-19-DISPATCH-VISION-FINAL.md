# Dispatch — The Vision Document

**Date:** 2026-03-19
**Authors:** Nigel Whaley (vision), Cipher (technical validation)
**Status:** DEFINITIVE — this is what we're building
**Classification:** Company North Star

---

## The One-Sentence Version

Dispatch is a voice-first Android agent that uses Claude Code as its brain, Google's AppFunctions as its hands, and your phone as its body — the first legitimate AI agent that operates your entire phone through the OS itself, not through API keys, not through accessibility hacks, not through credential exposure.

---

## The Origin Story

### What OpenClaw Did

OpenClaw went viral in 2026. Jensen Huang compared it to HTML and Linux at GTC. People bought dedicated Mac Minis, installed Claude Code CLI, ran OpenClaw on top of it, and used WhatsApp as the communication bridge. One AI brain connected to one chat platform.

**The problem:** To make the agent DO things, you had to expose your credentials. Gmail password. Bank API key. Calendar OAuth. Every service needed its own integration, its own API key, its own attack surface. Palo Alto Networks called AI agents "the biggest insider threat of 2026." 512 vulnerabilities. 341 malicious skills on ClawHub. 21,000 exposed instances on the public internet.

Anthropic killed third-party OAuth in February 2026 because of OpenClaw.

### What Nigel Saw

Nigel's insight: instead of the agent reaching OUT to every service through APIs, make the agent reach INTO the phone. The phone already has all your apps installed. Already authenticated. Already behind Android's permission sandbox. The agent doesn't need your Gmail password — it operates Gmail the same way you would.

**The original plan:** Use Android's Accessibility Service to let the agent tap, scroll, and type in apps on the phone. Like giving the agent a pair of hands that operate the touchscreen.

**The problem with that plan:** Android 17 is explicitly blocking non-accessibility apps from using the Accessibility API for automation. Google is closing that door.

### What Google Built Instead

**AppFunctions.** Google's answer to MCP, but for Android. Announced with Android 16, expanding in Android 17.

Every app can declare "here's what I can do" with structured Kotlin annotations. An AI agent discovers those declarations through the OS and calls them. No API keys. No credentials. No accessibility hacks. The OS brokers every call through Android's own security model.

**This is the door Google opened as they closed the accessibility one.** And it's a better door.

---

## The Architecture

```
Nigel speaks
    → Dispatch app (voice-first Android app)
    → POST /chat/stream to File Bridge on pop-os (via Tailscale)
    → File Bridge spawns Claude Code in stream-json mode
    → Claude reasons about what to do
    → Response streams back to Dispatch via SSE
    → Dispatch calls AppFunctions on the phone
    → Calendar, Messages, Email, Banking — whatever apps are installed
    → Results flow back to Claude for next step
    → Claude responds via streaming text + Kokoro TTS
    → Nigel hears the answer through earbuds
```

### The Three Layers

**The Brain — Claude Code on pop-os**
- Runs as a persistent subprocess via File Bridge's session manager
- Maintains conversation context across messages via stdin pipe
- Has access to DG's full infrastructure: cmail, dispatch, hooks, tools
- Stream-json mode provides real-time NDJSON events (21 event types)
- Sentence-level streaming for progressive TTS playback

**The Voice — Dispatch Android App**
- Voice-first communication via Kokoro GPU TTS (14 unique agent voices)
- FCM push for incoming voice notifications
- Earbud control: single-tap play/pause, double-tap voice reply
- Real-time SSE streaming for token-by-token text display
- Material3 themed conversation UI with sender grouping and message formatting

**The Hands — AppFunctions (Google's MCP for Android)**
- Every app on the phone exposes callable functions via `@AppFunction` annotations
- Discovery through AppSearch — the OS indexes available functions
- Execution through `AppFunctionManager` — the OS brokers the call
- No credential exposure — the app is already authenticated, the OS enforces permissions
- Provider side available NOW (any app can expose functions)
- Caller/agent side opening in Android 17 (Google confirmed "broadening capabilities")

### The Security Model

**OpenClaw's model:** Here are my credentials → agent calls APIs → hope nothing leaks → Palo Alto calls it the biggest insider threat of 2026

**Dispatch's model:** Agent never touches credentials → operates through the phone's own apps → Android's permission sandbox is the boundary → the OS brokers every cross-app call → AppFunctions provides structured, auditable function calls

No API keys to leak. No credentials to expose. No 21,000 exposed instances on the public internet. The phone IS the security boundary.

---

## What Exists Today (Built 2026-03-19)

### Android App (Dispatch)
- Native Kotlin/Jetpack Compose app on Pixel 9
- Material3 dark theme with themed bubble colors
- Four bubble types: Nigel (user), Agent (AI), Dispatch (voice), Tool (status)
- StreamingBubble with live token accumulation and tool status overlay
- JumpToBottom button, sender-run grouping, message formatter (bold, italic, code, clickable URLs)
- FCM push → GPU TTS playback via Media3 ExoPlayer
- Earbud double-tap voice reply via SpeechRecognizer → cmail
- MiniPlayerBar for persistent audio playback overlay
- Self-update system with OTA APK downloads
- Three-system separation: Sessions (AI) / cmail (messaging) / dispatch (voice)
- Clean seam contracts between systems via VoiceReplyCoordinator and CmailEventBus
- FCM token auto-registration to File Bridge
- Firebase Crashlytics for crash reporting

### Server (File Bridge on pop-os)
- 75 REST/SSE endpoints across 20 FastAPI routers
- Streaming chat relay: spawns Claude in stream-json mode, parses NDJSON → SSE with sentence boundary detection
- Persistent session manager: subprocess stays alive between messages via stdin pipe
- Session list from JSONL files with real-time chat watcher (500ms polling → SSE push)
- cmail gateway, TTS proxy (Kokoro on Oasis GPU), pulse broadcast, event stream
- API key authentication, async subprocess handling, 60s request timeout middleware
- Systemd service with auto-restart

### Infrastructure
- Pop-os as compute (Claude Code CLI + File Bridge + DG tools)
- Oasis GPU server for Kokoro TTS (14 voices, ~80ms TTFB)
- Tailscale mesh VPN (direct peer-to-peer, no public internet exposure)
- Bitwarden Secrets Manager for credential storage
- JSONL session files for conversation persistence
- Room database for local chat bubble caching on Android

---

## What's Next

### Phase 1: NOW — Expose Dispatch Functions (No Restrictions)

Add AppFunctions to Dispatch so Gemini and future agents can call it:
- `sendMessage(message, department, priority)` → Send voice dispatch
- `checkInbox()` → Read unread messages
- `getStatus()` → System health check
- `queryRoute(routeNumber)` → Route 33 customer lookup
- `checkDeliveryGaps(days)` → Find customers who haven't ordered

**This works today.** No permission gate for the provider side. Gemini on your Pixel 9 can discover and call these functions.

### Phase 2: Streaming TTS Wiring

Server side is done (chat_stream.py emits sentence events, POST /api/tts/stream exists). Wire the Android side:
- StreamEvent.Sentence → queue TTS request to Kokoro
- Progressive audio playback via existing serial executor architecture
- First audio in ~2-4 seconds instead of waiting for complete response

### Phase 3: When Google Opens the Caller Permission

When `EXECUTE_APP_FUNCTIONS` drops to `normal` protection (expected Android 17, Google I/O 2026):
- Implement function discovery via AppSearch
- Build intent-matching: user voice → Claude reasoning → function selection
- Wire Claude as the reasoning layer that decides which AppFunctions to call
- Ship Dispatch as a full local agent that orchestrates any app on the phone

### Phase 4: The Product

Package this for other people:
- One command → Prompt Generator births specialized agents from composable DNA
- Small business owner answers five questions → three agents deployed
- Stream-json is the wire protocol, channels are skins on the same NDJSON stream
- Per-agent seat pricing ($150-250/month)
- Positioned as "what comes after OpenClaw" when the hype cycle turns

---

## Why This Wins

### vs OpenClaw
- OpenClaw: one agent, one chatbot, credential exposure, 512 vulnerabilities
- Dispatch: multi-agent orchestration, voice-first, no credential exposure, OS-level security

### vs NemoClaw (Nvidia)
- NemoClaw: security wrapper on OpenClaw, still alpha, no multi-agent coordination
- Dispatch: production multi-agent system with composable identity, voice gateway, AppFunctions integration

### vs Everyone Else
- Nobody has production multi-agent orchestration + mobile-first + voice-first + AppFunctions integration
- Nobody has Claude Code as a programmable engine powering a native Android agent
- Nobody has composable agent identity from reusable DNA fragments (Prompt Generator)
- Nobody is positioned at the intersection of Android's AppFunctions ecosystem and Claude's reasoning capability

### The Timing
- Jensen said every company needs an agent strategy (GTC 2026)
- OpenClaw proved the market (viral adoption, massive security problems)
- Google built AppFunctions (the sanctioned path for app orchestration)
- Anthropic built Claude Code + Agent SDK (the best reasoning engine)
- Android 17 closes accessibility automation (forces migration to AppFunctions)
- 3-6 month window before the disillusionment cycle hits

**DG is the company that connects all of these pieces.** Built from a delivery truck by one person with a phone and a vision.

---

## Key Decisions Made (2026-03-19)

1. Self-built session routing over Anthropic Sessions API — direct phone→pop-os via Tailscale
2. File Bridge stays on pop-os — no cloud middleman
3. Three-system separation (sessions/cmail/dispatch) with formal seam contracts
4. Persistent sessions via stdin pipe — validated working
5. AppFunctions as the long-term hands mechanism — not accessibility hacking
6. Provider side NOW, caller side when Google opens it
7. Claude Code is the brain, not Gemini — Gemini is a caller of our functions, not our reasoning engine

---

## Sources

### AppFunctions
- [AppFunctions Overview — Android Developers](https://developer.android.com/ai/appfunctions)
- [Jetpack Library Releases](https://developer.android.com/jetpack/androidx/releases/appfunctions)
- [AppFunctionService API Reference](https://developer.android.com/reference/android/app/appfunctions/AppFunctionService)
- [AppFunctionManager API Reference](https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager)
- [AppFunctionsPilot Sample (GitHub)](https://github.com/FilipFan/AppFunctionsPilot)
- [Google details AppFunctions — 9to5Google](https://9to5google.com/2026/02/25/android-appfunctions-gemini/)
- [The Intelligent OS — Android Developers Blog](https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html)
- [In-Depth AppFunctions Analysis — OreateAI](https://www.oreateai.com/blog/android-16-appfunctions-api-indepth-analysis-of-applicationlevel-mcp-support-empowering-ai-scenarios/90b7ebd6407ec0c29cb143e9067144a3)
- [Android 17 Beta 1 Blog Post](https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html)

### Claude Code Internals (Cipher Research)
- Stream-JSON Mode: `reference/tools/stream-json-mode.md` — 21 event types, bidirectional stdin protocol
- Teleport & Remote: `reference/tools/teleport-and-remote.md` — Sessions API architecture
- Source: `dissected/cli-beautified.js` v2.1.71 (520,715 lines)

### Market & Competition
- [512 OpenClaw Vulnerabilities — Institutional Investor](https://www.institutionalinvestor.com/article/openclaw-ai-agent-institutional-investors-need-understand-shouldnt-touch)
- [341 Malicious ClawHub Skills — The Hacker News](https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub-skills-stealing-data-from-openclaw-users)
- [AI Agents as Insider Threats — Palo Alto Networks / The Register](https://www.theregister.com/2026/01/04/ai_agents_insider_threats_panw)
- [NemoClaw Launch — CNBC](https://www.cnbc.com/2026/03/10/nvidia-open-source-ai-agent-platform-nemoclaw-wired-agentic-tools-openclaw-clawdbot-moltbot.html)

### DG Project Documents
- `Dispatch/.project/TASKLIST.md` — All 20 tasks completed
- `Dispatch/.project/2026-03-19-dispatch-constraints-deep-dive.md` — Architecture decisions
- `Dispatch/.project/FILE-BRIDGE-API.md` — 75 endpoints documented
- `Dispatch/.project/2026-03-19-jetchat-vs-dispatch-ui-comparison.md` — UI audit
- `Dispatch/.project/2026-03-19-appfunctions-deep-dive.md` — AppFunctions technical research
- `Dispatch/.project/STRATEGIC-BRIEF.md` — Market positioning
- `Dispatch/.project/2026-03-17-dispatch-streaming-deep-dive.md` — Streaming architecture

---

*This document is the north star. Everything we build points here.*

*Written by Cipher. Inspired by a delivery driver who saw the future from the cab of his truck.*
