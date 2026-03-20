# Research: Agent Runtime & Deployment Options

**Date:** 2026-03-17
**Sources:** Anthropic SDK docs, platform comparisons, infrastructure pricing

---

## Key Finding: Claude Agent SDK Is The Runtime

Claude Code has been formalized as the **Claude Agent SDK** — same tool loop and context management, but as a library you call programmatically.

**What it can do:**
- Run headlessly (no TTY required) — CI/CD, production automation, custom apps
- Multiple concurrent instances on one machine (each query() is independent async)
- Session persistence with resumable IDs (maintain context across days/weeks)
- Built-in tools: Read, Write, Edit, Bash, Glob, Grep, WebSearch, WebFetch
- Hooks at every lifecycle event
- MCP server integration
- Filesystem-based config (CLAUDE.md, slash commands)
- Subagent spawning (two levels deep — parent + child, no grandchildren)

**What it cannot do:**
- No built-in web UI
- No user management
- No billing isolation per customer
- No always-on daemon with message queue
- SDK fires on demand and exits — you build the wrapper

**Auth constraint:** Anthropic prohibits third-party apps from using claude.ai login. Must use API keys — either we absorb costs on our key, or customers provide their own.

---

## Cost Reality: Per-Customer Economics

**LLM API (dominant expense):**
- Agents make 3-10x more LLM calls than simple chatbots
- Light SMB agent (~20 tasks/day): $10-30/month in API
- Heavy agent (research, multi-turn): $50-150/month
- Subagents and long contexts compound quickly

**Compute hosting:**
- Docker Compose on Hetzner VPS: $5-20/month total (handles ~20 customers)
- Railway/Render: $5-20/month usage-based
- Coolify (self-hosted Heroku): zero platform fees on your own VPS

**Realistic per-customer cost: $20-70/month at moderate use**

**Is $150-250/month per-agent-seat viable?** Yes, with margin. The risk is heavy-use customers. Mitigations: usage caps, tiered pricing, or customer-supplied API keys.

---

## Deployment: Docker Compose Wins

For the first 20 customers, the stack is:
- Docker Compose on a single VPS (Hetzner ~$5-20/month)
- Per-customer isolation via separate containers or database namespacing
- Coolify for management UI (open source, self-hosted)
- Postgres for inter-agent messaging and state

Skip Kubernetes entirely. Skip managed agent platforms (their $99-999/month fees kill margins at low volume).

---

## User Interface: Messaging Integration Is Fastest

Ordered by friction:
1. **Telegram/WhatsApp/Slack/Discord** — zero new software for user (lowest friction)
2. **Web chat interface** — URL they visit, talk to agents (medium friction)
3. **Mobile app** — highest quality, highest build cost

**For Phase 1:** Telegram bot or web chat. Both buildable on top of Agent SDK with a lightweight FastAPI server. Messaging integration is compelling for SMB clients who live on their phones — and it's the fastest to deploy.

---

## Inter-Agent Communication

cmail's move toward SQLite/database-backed messaging is architecturally sound. For hosted deployments:
- SQLite works for single-server (current scale)
- Postgres scales to multi-server when needed
- File-based breaks in containerized environments unless shared volumes
- Message queues (Redis) add resilience but also complexity — save for later
