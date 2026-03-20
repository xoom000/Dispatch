# Component Refactor Plan

**Date:** 2026-03-19
**Status:** EXECUTING

## Team Structure — 4 Sonnet Agents

### Agent 1: "bubbles" — Extract Messaging Components from MessagesScreen
**Source:** MessagesScreen.kt (683 lines → ~200 after extraction)
**Creates:**
- `components/bubbles/NigelBubble.kt`
- `components/bubbles/AgentBubble.kt`
- `components/bubbles/DispatchBubble.kt`
- `components/bubbles/ToolBubble.kt`
- `components/bubbles/StreamingBubble.kt`
- `components/bubbles/SendingIndicator.kt`
- `components/MessageFormatter.kt` (messageFormatter + symbolPattern + enum)
- `components/JumpToBottom.kt`
**Edits:** MessagesScreen.kt — replace private composables with imports

### Agent 2: "inputbar" — Extract InputBar + Fix ChatScreen
**Source:** MessagesScreen.kt lines 326-403, ChatScreen.kt
**Creates:**
- `components/InputBar.kt` — full input bar with callbacks: onSend, onAttach, onEmoji, onImage, onMic
**Edits:** MessagesScreen.kt — replace inline input bar with InputBar component
**Also fixes ChatScreen.kt:**
- Pass department to AgentAvatar instead of UUID
- Make department the primary label, alias/summary the subtitle
- Smart timestamps (today=time, yesterday="Yesterday", older=day name)
- Add last message preview line

### Agent 3: "activity" — Extract ActivityScreen Components
**Source:** ActivityScreen.kt (958 lines → ~300 after extraction)
**Creates:**
- `components/sessions/SessionCard.kt`
- `components/records/UserRecordBubble.kt`
- `components/records/AssistantRecordBubble.kt`
- `components/records/ToolCallChip.kt`
- `components/records/ToolResultChip.kt`
- `components/records/SystemRecord.kt`
**Edits:** ActivityScreen.kt — replace privates with imports

### Agent 4: "utilities" — Shared Utilities + Remaining Screens
**Creates:**
- `util/TimeFormatter.kt` — ONE unified time formatter replacing all 6 duplicates
- `components/cards/TaskCard.kt` (from BoardScreen)
- `components/cards/PulsePostCard.kt` (from PulseScreen)
- `components/cards/HistoryCard.kt` (from HistoryScreen)
- `components/cards/EventCard.kt` (from EventFeedScreen)
- `components/TagBadge.kt` (from PulseScreen)
- `components/AssigneeBadge.kt` (from BoardScreen)
**Edits:** All source screens — replace private composables + time formatters with imports

## Material3 Rules (ALL agents must follow)

1. Use `MaterialTheme.colorScheme.*` — NEVER hardcode colors
2. Use `MaterialTheme.typography.*` — NEVER hardcode text styles
3. First parameter is always `modifier: Modifier = Modifier`
4. State hoisting — components take state as params + callbacks, never own state
5. Use `Surface` for elevated containers with `tonalElevation`
6. Use theme-aware shapes from `MaterialTheme.shapes`
7. All components must be public, in their own file, with @Preview
