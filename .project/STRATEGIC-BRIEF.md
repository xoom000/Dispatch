# Digital Gnosis — Agent Platform Strategic Brief

**Date:** 2026-03-17
**Authors:** Nigel Whaley (vision), Engineering (research & technical validation)
**Status:** Working draft — pre-planning

---

## The Opportunity

Jensen Huang declared at GTC 2026 that every company needs an "OpenClaw strategy." He compared it to HTML and Linux in significance. The agentic AI market is $9B today, heading to $50-140B by 2034. Gartner says 40% of enterprise apps will have agents by end of 2026, up from under 5% in 2025.

**But only 8.6% of companies have agents in production.** Most are stuck in pilot purgatory. Small business AI adoption has actually *declined* to 28%. The barriers: budget, skills gap, integration complexity, unclear ROI.

The demand is massive. The delivery is failing. The gap is implementation.

---

## The OpenClaw Window

OpenClaw is at peak hype. It's also deeply flawed:
- 512 vulnerabilities found in January audit, 8 critical
- 341 malicious skills on ClawHub (single coordinated attack)
- 21,000+ exposed instances on the public internet
- No inter-agent coordination (their "multi-agent" is just message routing)
- No governance, audit trails, or enterprise security
- Palo Alto Networks called AI agents "the biggest insider threat of 2026"

**The cycle:** Awareness (now) → Adoption (people install it) → Disillusionment (security incidents, single-agent limits, no business workflows) → **Search for something real.**

That search is our entrance. Window: 3-6 months.

---

## What DG Proved

DG is a company running as code. 29 AI agents organized into departments, coordinated through file-based messaging, deployed through a composable prompt factory, operated from a phone via a voice-first Android gateway. Built by one person from a delivery truck.

What matters isn't the specific implementation — it's what we proved:
- Multi-agent orchestration works in production
- Composable agent identity (Prompt Engine) creates specialized agents from reusable DNA fragments in under a second
- Inter-agent coordination (cmail) enables agents to delegate, report, and collaborate
- Voice-first mobile control lets one person operate an entire agent fleet
- The whole thing runs on standard infrastructure: Linux, Python CLIs, Claude Code, JSON

**Key insight from Nigel:** "All we are doing is throwing JSON at AI and building Linux tools they can use." The architecture is runtime-agnostic. The value is in the composition and coordination patterns, not the specific LLM or platform.

---

## The Product Vision

### One Command, Your Team

OpenClaw gives you `npm install openclaw` → a chatbot on WhatsApp.

We give you one command → a coordinated team of agents. Not a chatbot. Agents that do work, coordinate with each other, take direction from you without losing context, and execute without ego, politics, or bad days.

Not just for companies. For anyone who needs a good team behind them. A solopreneur launching a business. A person pushing through a hard season. Someone building something bigger than they can do alone. The technology is the same — the use case is yours.

**Nigel's proof:** A delivery driver built a 29-agent team from his phone that runs an entire operation. No office. No employees. No MBA. Just a vision and a team that doesn't lose context, doesn't have bad days, and doesn't play politics.

### The Brood Mother

Prompt Generator (PG) is the core product. One agent that dynamically creates other agents.

**How it works today:**
1. PG selects personality, reasoning, behavior, communication, security, and tool fragments from a library
2. Composition takes 0.25 seconds
3. Output: a complete, specialized agent identity (40KB system prompt)
4. Deploy to a worktree, invoke with the composed prompt
5. **Total time from request to live agent: under 60 seconds**

**What makes this different from OpenClaw skills:**
- OpenClaw skills = markdown files injected into one agent's prompt (prompt injection)
- PG = composable DNA that creates entirely new, specialized agents with distinct identities, behaviors, and tool access

PG doesn't configure a chatbot. PG *births* agents.

### The Middle Management Insight

**Nigel's thesis:** The most inefficient layer in any business is middle management. Vision comes from the top. Execution happens at the bottom. The middle layer is where context dies — lost in translation, distorted by ego, degraded by human inconsistency.

**The honest assessment:** This is partially right. Good middle managers handle exceptions, judgment calls, and the human side of organizations. But the information routing, context translation, and consistent execution? That IS what agents do better. Perfect recall. Zero ego. Zero politics. No bad days.

**The product framing:** We're not replacing managers. We're adding an execution layer that takes direction from leadership and translates it into action without context loss. The humans stay for judgment, relationships, and exceptions. The agents handle everything that should be consistent, fast, and reliable.

**The adaptive communication layer (future):** An agent gateway that detects individual communication styles and adjusts its approach per person. Dynamic prompt injection based on interaction patterns — like real-time personality-aware weight adjustment at the prompt level.

**Framing matters:** "We profile employees and manipulate communication" = lawsuit. "Each person gets a personalized AI assistant that works in their preferred style" = benefit. Same technology, opposite reception.

---

## Market Position

### What exists:
- **OpenClaw** — single-agent personal assistant, massive adoption, massive security problems
- **NemoClaw** — Nvidia's enterprise wrapper, still alpha, focuses on security not coordination
- **CrewAI / LangGraph / SuperAGI** — frameworks for developers, not products for businesses
- **AWS Bedrock Agents** — enterprise default for AWS shops, but requires serious engineering

### What doesn't exist:
- Production multi-agent orchestration for business operations (**we have this**)
- Mobile-first agent fleet management (**we have this**)
- Composable agent identity from reusable DNA fragments (**we have this**)
- Voice-first agent communication with per-agent personalities (**we have this**)
- One-command business agent deployment (**we need to build this**)

### Nobody is doing what we're doing.

---

## The Path

### Phase 0: Finish Eddie (now — weeks away)
Close the first client engagement. Prove we can deliver.

### Phase 1: Define "One Command" (immediately after Eddie)
- What does the smallest viable version look like?
- Working concept: run a command, answer 5 questions about your business, PG spins up 3 agents (operations, customer-facing, coordinator) pre-wired to talk to each other
- Define the fragment library needed for business archetypes (customer service, scheduling, bookkeeping, operations)
- This is a content + architecture problem, not a deep engineering problem

### Phase 2: Rat Maze (as soon as Phase 1 is demoable)
- Find one tiny business — smaller than DG
- Deploy the system for free or dirt cheap
- Observe from outside: what works, what breaks, what they actually use
- This is the validation we can't get from our own usage

### Phase 3: Iterate (based on Phase 2 learnings)
- Fix what broke
- Build what was missing
- Refine the one-command experience

### Phase 4: Market Entry (targeting the disillusionment window)
- Real product with real results from a real business
- Positioned as "what comes after OpenClaw"
- Timing: 3-6 months from now, when the hype cycle turns

---

## Pricing Direction

Market research shows per-agent seat pricing is emerging as the model for multi-agent platforms:

- **Setup fee:** One-time deployment ($1,500-3,000)
- **Per-agent seat:** Monthly recurring ($150-250/agent)
- **Example:** Restaurant gets 3 agents (reservations, inventory, customer feedback) = $450-750/month
- **Ongoing maintenance:** Included in seat price or tiered

This maps naturally to "your business, staffed by specialized AI agents" — intuitive value for buyers.

---

## Architecture Breakthrough: Stream-JSON Mode

**Discovered during session:** Claude Code's `--output-format stream-json` mode is a full bidirectional agent control protocol — 21 event types streaming as NDJSON (newline-delimited JSON). This is the same protocol the Claude Agent SDK uses internally to communicate with child processes.

**What this means:** We don't need to build a gateway from scratch. Claude Code IS the gateway.

**The architecture:**
1. PG composes the CLAUDE.md and system prompt for an agent
2. A thin wrapper spawns Claude Code in `--print --output-format stream-json --verbose` mode
3. User input comes from any source (web, Telegram, mobile app) and gets piped in as stream-json
4. Agent responses, tool calls, subagent spawns, hook events stream back as NDJSON
5. The wrapper routes those events to whatever frontend the user is on

**Event types include:** assistant responses, tool use progress, subagent task spawning/completion, hook lifecycle, cost tracking, session compaction, system status. Full agent observability out of the box.

**Bidirectional input:** User messages, permission handling, environment variable injection — all controllable through stdin in stream-json format.

**Why this matters:**
- No custom agent runtime to build — Claude Code handles reasoning, tools, sessions
- No custom protocol to design — Anthropic already defined and supports it
- Channel-agnostic by default — any frontend that can read NDJSON can be a client
- Telegram, web chat, WhatsApp, mobile app = skins on the same stream
- The wrapper is thin — maybe 500 lines of Python routing events between channels and Claude Code processes

**Nigel's insight:** He'd been pushing stream-json exploration and created the Cipher agent to investigate it. Nobody followed through. This was the right call from the start.

---

## Revised Architecture: The Queen as Two Agents

Research (from Anthropic, LangGraph, CrewAI, AutoGen) says the queen should be split:

**Agent 1: Intake Agent**
- Conversational, warm, exploratory
- Interviews the user (5 high-signal questions)
- Researches in real-time during the interview
- Outputs a structured deployment plan (not prose)
- Spawns the first visible agent by question 3 (time-to-first-artifact)

**Agent 2: Orchestrator**
- Reads the structured plan from intake
- Selects agent templates from PG's fragment registry
- Composes and deploys the team
- Wires inter-agent communication
- Stays available for ongoing coordination (event-driven, not always-on)

Both agents run over stream-json. The intake agent's events render as a conversation. The orchestrator's events render as deployment progress.

---

## Open Questions

1. ~~**Platform choice:**~~ **ANSWERED** — Claude Code in stream-json mode, via Agent SDK
2. ~~**Channel strategy:**~~ **ANSWERED** — Stream-JSON is the protocol. Channels are skins.
3. **Hosting model:** Do we host for customers (managed), give them a self-hosted package, or both?
4. **First maze candidate:** Who's the right tiny user to test with?
5. **Naming:** Is this still Digital Gnosis? Or does the product need its own identity?
6. **The adaptive communication layer:** Is this Phase 1 or Phase Later?
7. **First frontend:** Web chat or Telegram bot as the first skin on the stream?

---

## Supporting Research

All research artifacts saved in `engineering/re/openclaw/`:
- `RE-FINDINGS.md` — Full reverse engineering of OpenClaw v2026.3.13
- `MARKET-RESEARCH-openclaw-gaps.md` — Security issues, competitor landscape, enterprise walls
- `MARKET-RESEARCH-positioning.md` — Market size, SMB barriers, pricing models, voice-first landscape
- `knowledge/openclaw-vs-dg.md` — Architecture comparison (DG vs OpenClaw)

---

*This document captures the strategic conversation of 2026-03-17 between Nigel and Engineering. It is a working brief, not a final plan. Next step: Nigel reviews, adds context, and we start Phase 1 definition.*
