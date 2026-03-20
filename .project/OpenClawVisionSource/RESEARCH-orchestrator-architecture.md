# Research: Orchestrator-Agent Architectures

**Date:** 2026-03-17
**Sources:** Anthropic, LangGraph, CrewAI, AutoGen documentation and engineering blogs

---

## Key Finding: The Queen Should NOT Be One Agent

Every production framework converges on the same lesson: **the orchestrator that tries to understand everything eventually breaks under its own context weight.**

The research reveals the queen concept should be split into TWO agents, not one:

### Agent 1: The Intake Agent (Interview)
- Narrow scope: ask questions, collect answers, research in real-time
- Outputs a structured config object (not natural language)
- Stateless — runs once, produces a plan, done
- Talks directly to the user

### Agent 2: The Orchestrator (Deployment & Coordination)
- Reads the structured config from the intake agent
- Queries the agent registry for matching templates
- Assembles the team, deploys agents, wires communication
- Stays running as the coordination layer
- Never touches raw user requirements — works from structured config only

**Why this separation matters:**
- The intake agent needs to be warm, conversational, exploratory
- The orchestrator needs to be deterministic, predictable, testable
- Conflating them creates a god-object that's good at neither

---

## What Orchestrators Actually Own (Across All Frameworks)

Three things, consistently:
1. **Task decomposition** — break input into subtasks
2. **Routing** — send subtasks to the right agents
3. **Result synthesis** — collect final outputs into coherent result

What they explicitly DO NOT own:
- Tool execution (subagents)
- Domain knowledge (subagents)
- The interview/discovery session (separate intake agent)
- Agent template definitions (registry)
- Persistence between sessions (infrastructure)

---

## Anti-Patterns: What Breaks

1. **Context window collapse** — orchestrator tracking all subagent state hits context limits. Fix: subagents maintain own scratchpads, surface only final outputs.

2. **Compounding error** — every routing decision is a failure point. Wrong decision at step 2 poisons all subsequent steps. Fix: human checkpoints, validation gates.

3. **Serialization failure** — orchestrator with complex logic in code can't be persisted or restarted. Fix: declarative config over procedural logic.

4. **Sequential bottleneck** — orchestrator processing everything serially becomes throughput ceiling. Fix: parallel execution where tasks are independent.

5. **Model capability ceiling** — orchestrator prompt encoding all business logic, agent capabilities, and state degrades as complexity grows. Fix: keep orchestrator prompt simple, push knowledge to subagents.

---

## Agent Spawning: Templates Win Over Dynamic Generation

Every framework defaults to pre-defined templates with known costs and behaviors. Dynamic composition is supported but treated as an advanced pattern with explicit caveats.

**The tradeoff:**
- Pre-defined templates: predictable, debuggable, cost-controlled
- Fully dynamic: flexible but introduces unbounded cost, unpredictable behavior, testing nightmares

**The solution:** Maintain an agent registry (known templates with known costs). The orchestrator SELECTS from the registry rather than generating agents from scratch.

This maps to PG's fragment library. The fragments ARE the registry. The queen doesn't invent new agent types — she composes from known, tested fragments.

---

## Minimum Viable Orchestrator

Five capabilities:
1. **Task decomposition** — the one LLM-reasoning step
2. **Agent registry access** — read-only view of what agents exist
3. **Routing logic** — match subtasks to agents
4. **Termination conditions** — know when to stop (non-negotiable)
5. **Result synthesis** — collect and present final outputs

**The Claude Agent SDK constraint:** Subagents cannot spawn their own subagents — hierarchy is exactly two levels deep. This means the orchestrator is the only agent that can spawn others. This is actually a GOOD constraint — it prevents runaway recursive spawning.

---

## Persistence Model

No framework has a native "put orchestrator to sleep, wake on event" pattern. Production systems solve this at the infrastructure layer:
- Job queues (process events as they arrive)
- Webhooks (wake on external trigger)
- Cron (periodic check-ins)

The Claude Agent SDK supports session IDs that can be resumed across days/weeks — meaning the orchestrator can be dormant between user interactions and resume with full context.
