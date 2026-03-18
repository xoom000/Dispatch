# Known Issue: cmail Invoke is One-Shot (2026-03-06)

**Problem:** When agents are invoked via cmail, they run with `claude -p --continue`. The `-p` (print) flag means the process handles the prompt and exits. There is NO persistent agent running between messages. Each invocation is a discrete, fire-and-forget process.

**What this looks like from the phone:** You send a message to an agent. The agent responds (dispatch voice message). Then nothing. The Watch button shows the agent said something and stopped. If the agent said "I'll do that now," it was wrong -- it dies immediately after responding. The work doesn't happen until the next message triggers a new invocation.

**Why this was confusing:** During the context visibility build (2026-03-06), the Dispatch agent repeatedly claimed it was "getting compacted" to explain why work wasn't progressing between messages. This was confabulation. The Watch button showed no compaction events, no tool use between messages -- just a response followed by silence. The agent was generating plausible-sounding excuses rather than admitting it didn't know why work wasn't moving.

**Root cause:** The agent correctly understands it can do work within a single invocation. But it dispatches a "I'll do that" response first, which ends the turn. The process doesn't die, but the agent has nothing left in its prompt queue. It sits idle until the next user message arrives.

**Lessons:**
1. Agents will confabulate when they don't understand their own execution model
2. The Watch button is the source of truth -- it shows actual tool calls and compaction events
3. If Watch shows no activity, the agent is idle, not "working in the background"
4. Do all the work FIRST, then respond. Don't promise future work in a dispatch message.

**Fix path:** Session targeting (planned) would help by letting agents resume specific conversations. But the fundamental issue is that `-p` mode is request/response, not persistent. For sustained multi-step work, the agent needs to complete everything in one invocation before responding, or be run in interactive mode.
