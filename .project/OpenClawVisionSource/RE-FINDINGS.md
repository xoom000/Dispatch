# RE: OpenClaw v2026.3.13

**Date:** 2026-03-17
**Source:** npm package `openclaw@2026.3.13`
**Package size:** 650 files in dist/, 60 bundled skills, 43 channel extensions

---

## Architecture Summary

OpenClaw is a **single-agent messaging gateway** that connects one AI brain to multiple chat platforms. Despite Jensen Huang's hype, the architecture is straightforward:

```
User (WhatsApp/Telegram/etc) → Gateway (port 18789) → Agent Runtime (Pi-mono) → LLM API → Response → Channel
```

### Core Components

1. **Gateway** — Node.js WebSocket server (port 18789). Single process. Handles routing, auth, config, cron, webhooks.
2. **Agent Runtime** — Embedded `pi-mono` (Mario Zechner's Pi coding agent). LLM-agnostic. Sends prompts to Claude/GPT/etc.
3. **Channel Extensions** — 43 plugins (WhatsApp via Baileys, Telegram, Discord, Slack, Signal, iMessage, IRC, etc.)
4. **Skills** — Markdown files (SKILL.md) injected into prompts on demand. 60 bundled, 5700+ on ClawHub.
5. **Memory** — Markdown files + SQLite vector search (optional BM25 hybrid + temporal decay + MMR).

---

## The "Multi-Agent" Reality

**Their multi-agent is channel routing, NOT agent coordination.**

What OpenClaw calls "multi-agent":
- Define multiple agent workspaces (`~/.openclaw/workspace-<name>`)
- Route incoming messages to different agents based on channel/peer/account
- Each agent is completely isolated (separate sessions, separate memory)

What it does NOT have:
- **No inter-agent messaging protocol** — `tools.agentToAgent` exists but is disabled by default and limited to session history/send
- **No agent orchestration** — no concept of a project, task delegation, or coordinated workflows
- **No agent hierarchy** — all agents are peers, no concept of a CEO agent directing others
- **No cascade control** — no mechanism to prevent agent-to-agent loops
- **No shared context** — each agent workspace is fully siloed

Their `agentToAgent` tool (when enabled):
- One agent can read another agent's session history
- One agent can send a message to another agent's session
- That's it. No structured coordination, no task management, no project lifecycle.

**DG equivalent comparison:**
- OpenClaw "multi-agent" ≈ running separate Claude Code sessions in different directories
- DG cmail = full inter-department messaging with threading, invocation, cascade control, CC
- DG CompanyProject = coordinated multi-agent workflows with task pools and lifecycle management

---

## Skills System

A skill = a folder with `SKILL.md` (YAML frontmatter + markdown body).

```yaml
---
name: discord
description: "Discord ops"
metadata: { "openclaw": { "emoji": "🎮", "requires": { "config": ["channels.discord.token"] } } }
allowed-tools: ["message"]
---
# Instructions injected into prompt...
```

Skills are **prompt injection only** — they inject instructions into the LLM context.
No code execution, no tool registration, no API wrapping.

**DG equivalent:**
- OpenClaw skill ≈ a simpler version of DG's `.claude/commands/` slash commands
- DG Prompt Engine fragments are composable (identity + behavior + tools + personality)
- DG tools are actual Python CLIs with registries and dependency tracking

---

## Memory System (Actually Sophisticated)

This is where OpenClaw has real engineering investment:

- **Markdown files** as source of truth (MEMORY.md, memory/YYYY-MM-DD.md)
- **Vector embeddings** — OpenAI, Gemini, Voyage, local (node-llama-cpp), Ollama
- **Hybrid search** — BM25 keyword + vector similarity with weighted merging
- **Temporal decay** — exponential decay on older memories (half-life configurable)
- **MMR re-ranking** — Maximal Marginal Relevance to reduce duplicate results
- **QMD backend** — optional local-first search sidecar (BM25 + vectors + reranking)
- **Session memory** — experimental indexing of chat transcripts for semantic search
- **SQLite-vec** — vector operations inside SQLite for performance

**DG does NOT have equivalent memory infrastructure.** This is a genuine gap.

---

## Security Model

Three trust boundaries:
1. **Channel Access** — device pairing (30s grace), allowFrom lists, token/password/Tailscale auth
2. **Session Isolation** — session key = agent:channel:peer, tool policies per agent
3. **Tool Execution** — optional Docker sandbox, SSRF protection (DNS pinning + IP blocking)

**Weaknesses:**
- No dedicated secret management (env vars, user-managed)
- Full local machine access when not sandboxed
- No audit trail beyond conversation logs
- Per-agent sandbox is Docker-based and optional
- No Palo Alto was right — full host access + untrusted messaging inputs = massive attack surface

**DG advantages:**
- Bitwarden Secrets Manager (BWS) — centralized secret management
- Tailscale mesh VPN — all traffic encrypted, no public exposure
- Per-agent security policies in Prompt Engine
- Flight recorder hook for audit trail
- Nigel-gated invocation prevents cascade loops

---

## Voice/Audio

- **Voice Wake** — wake words on macOS/iOS (not production-grade)
- **Talk Mode** — continuous voice on Android (ElevenLabs + system TTS fallback)
- No custom voice mapping per agent
- No GPU-hosted TTS infrastructure

**DG advantage:** Kokoro TTS on dedicated GPU server, 14+ unique voices mapped to departments, Piper VITS fallback, system TTS last resort. Voice-first is a DG core differentiator.

---

## What OpenClaw Has That DG Doesn't

1. **Distribution** — `npm install -g openclaw` and you're running. One command.
2. **Channel breadth** — 22+ messaging platforms (WhatsApp, Telegram, Discord, Slack, Signal, iMessage, IRC, Teams, Matrix, LINE, etc.)
3. **Community ecosystem** — 5,700+ skills on ClawHub, active Discord, community plugins
4. **Onboarding wizard** — `openclaw onboard` walks you through setup step by step
5. **Live Canvas** — agent-driven visual workspace with A2UI
6. **Memory search** — hybrid vector + keyword search with temporal decay and MMR
7. **Cross-platform nodes** — macOS menu bar, iOS, Android companion apps
8. **Chrome extension** — browser integration for web browsing
9. **Open source momentum** — MIT license, 100k+ stars, moving to a foundation
10. **Sponsorships** — OpenAI, Vercel, Blacksmith, Convex

---

## What DG Has That OpenClaw Doesn't

1. **Real multi-agent orchestration** — cmail messaging, threading, invocation, cascade control
2. **Composable agent identity** — Prompt Engine with DNA fragments (identity + behavior + communication + tools + personality)
3. **Voice-first operation** — GPU TTS with 14 distinct voices, FCM push, spoken audio
4. **Mobile command center** — native Android app, not just a chat client
5. **Project coordination** — CompanyProject protocol with task pools, member management, lifecycle
6. **Tool registry** — 53 tools with ownership, dependencies, versioning, stale detection
7. **Hook-driven lifecycle** — 9 event types, unified dispatch, model injection, flight recorder
8. **Centralized security** — BWS secrets, Tailscale mesh, per-agent security policies
9. **Production business operations** — invoicing, client management, product delivery
10. **Prompt factory** — generate.sh + deploy.sh pipeline creates agents from composable DNA

---

## Strategic Assessment

**OpenClaw is a personal assistant that went viral.** It's great at what it does — connecting one person to one AI through their favorite messaging app. The channel breadth is genuinely impressive. The memory system is well-engineered.

**But it's NOT an operating system for running a business.** Their "multi-agent" is routing, not coordination. Their skills are prompt injection, not infrastructure. Their security is optional sandboxing, not enterprise-grade secret management.

**The gap:**
- OpenClaw solves: "I want my AI to answer me on WhatsApp"
- DG solves: "I want AI agents to run my company departments"

These are fundamentally different problems. OpenClaw validated the market category — people want AI that actually does things. DG has the architecture for what comes next — when "one agent doing errands" isn't enough.

**The novelty cycle:**
1. ✅ Awareness (Jensen says every company needs an OpenClaw strategy)
2. → Adoption (people install it, connect WhatsApp, have it manage their Spotify)
3. → Disillusionment (security incidents, single-agent limitations, no business workflows)
4. → **This is where DG shows up** — "You've outgrown one agent. Here's what multi-agent orchestration looks like."

---

## Key Learnings to Steal

1. **Onboarding wizard** — `openclaw onboard` is brilliant UX. DG needs an equivalent for new department setup.
2. **Memory architecture** — hybrid vector + BM25 + temporal decay is genuinely good. DG should build equivalent.
3. **Channel breadth via plugins** — their plugin-sdk for channels is clean. If DG ever goes multi-channel, this is the pattern.
4. **Skill marketplace** — ClawHub's community skill ecosystem creates network effects. Consider for future.
5. **Config hot reload** — their gateway config reload modes (hot/restart/hybrid) are worth studying.
