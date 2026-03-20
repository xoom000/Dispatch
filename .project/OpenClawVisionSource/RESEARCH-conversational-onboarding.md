# Research: Conversational Onboarding & Interview-Driven Configuration

**Date:** 2026-03-17
**Source:** Web research + established UX/product patterns

---

## Key Finding: The Queen Interview Pattern

The research converges on a clear pattern for the queen's interview:

### The Five High-Signal Questions

From discovery interview methodology (Teresa Torres, Jobs-to-be-Done framework):

1. **Who are you in the context of this system?** — Role/identity, determines complexity level
2. **What does success look like in 30 days?** — Goal, determines which agents to prioritize
3. **What are you replacing or coming from?** — Prior context, mental model anchoring
4. **Who else is involved?** — Team/collaboration, determines coordination needs
5. **What's the one thing that would make you stop using this?** — Anti-goal, highest signal question (most underused)

**Sweet spot: 3-5 mandatory questions + one open-ended "anything else?"**
- 3 questions: highest completion, lowest config quality
- 7 questions: best config fit, 40% drop-off
- 5 questions: optimal balance (Intercom internal research)

### Question Flow Structure

All successful conversational onboarding follows this order:
1. Identity (who are you)
2. Context (what's your situation)
3. Intent (what do you want to accomplish)

**Never reverse this order.**

### Critical UX Principles

- **First artifact before last question.** Show something real DURING the interview, not after. Users who see their first agent spawn before question 5 will have dramatically higher engagement.
- **"Not sure" is always valid.** Every question needs a default path. Blocking on ambiguity is a fatal onboarding error.
- **Show examples, not options.** "Do you want X or Y?" fails. "Here are three setups like yours — which looks familiar?" succeeds.
- **Consequence-framing.** Tell users WHY a question matters: "This affects how your agents communicate with you."

### Time-to-Value

- Users who experience activation in first session: 3-4x higher 30-day retention
- If no meaningful experience within 5-8 minutes: drop-off probability spikes
- **Resolution: make the interview output the product demo.** The onboarding conversation generates live artifacts you're already using by question 3.

---

## AI-Driven Team Composition Pattern

The pattern is consistent across RPGs, sports analytics, military planning, and consulting:

1. **Audit current state** — what do you have?
2. **Define objective** — what are you trying to do?
3. **Identify gap** — what's missing between current capability and required capability?
4. **Recommend archetypes** — fill gaps with role templates, not specific configurations
5. **Rank/customize** — optionally let user adjust the recommendation

**Key insight:** No system recommends a team purely from the objective. They ALL require both "what do you have" AND "what do you need."

For the queen: she can't just ask "what do you want to do?" — she needs to understand what the user ALREADY has (existing tools, skills, team) AND what they need. The gap analysis produces the agent team.

---

## Adaptive Questioning: Hybrid Wins

- **Pure decision trees** work for <20 configurations, break beyond that
- **Pure LLM free-form** fails because users without domain knowledge give low-signal answers
- **Hybrid approach:** 3-5 structured questions (consistent signal) → open free-form for nuance

This maps perfectly to the queen interview: structured questions to anchor the context, then free conversation to surface edge cases and personality.

---

## Design Implications for the Queen

1. The queen asks 5 questions, not 20
2. After question 2-3, she spawns the FIRST agent live — user sees something real immediately
3. "Not sure" routes to sensible defaults, not dead ends
4. Question order: who are you → what's your situation → what's the goal → who else → what would make you quit
5. The interview IS the product demo — agents start appearing during the conversation
6. Gap analysis, not feature selection — queen figures out what's needed from what's missing
