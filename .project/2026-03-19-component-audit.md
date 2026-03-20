# Dispatch App — Component Audit

**Date:** 2026-03-19
**Author:** Cipher (3x haiku auditors)
**Problem:** Components are slammed into screen files as private composables instead of being extracted into reusable components.

---

## Summary

**Total screen files:** 11
**Total composables found in screens:** 48
**Total helpers found in screens:** 16
**Reusable components folder:** 3 files (AgentAvatar, DepartmentPicker, MiniPlayerBar)

Almost every screen has private composables that should be extracted.

---

## Screen-by-Screen Breakdown

### MessagesScreen.kt — 683 lines — THE WORST OFFENDER
**9 composables, 2 helpers — all private**

COMPOSABLES:
- messageFormatter (private) [58-139] — Converts text to AnnotatedString with inline Markdown + clickable links
- JumpToBottom (private) [145-171] — Animated FAB for scrolling to bottom
- MessagesScreen (public) [175-404] — Main screen with bubbles, streaming, input bar
- NigelBubble (private) [408-454] — User message bubble with styled text
- AgentBubble (private) [456-502] — Agent response bubble
- DispatchBubble (private) [504-575] — Voice dispatch bubble with replay button
- ToolBubble (private) [577-594] — Tool execution indicator
- StreamingBubble (private) [596-661] — Live streaming text with thinking indicator
- SendingIndicator (private) [663-682] — "Sending..." loading indicator

HELPERS:
- symbolPattern (private lazy) [52-54] — Regex for Markdown matching
- SymbolAnnotationType (private enum) [56] — Link/Person annotation types

**Should extract:** InputBar, all 5 bubble types, messageFormatter, JumpToBottom, StreamingBubble

---

### ActivityScreen.kt — 958 lines — SECOND WORST
**11 composables, 2 helpers — all private**

COMPOSABLES:
- AgentsScreen (public) [84-127] — Sessions + Events tabs with segmented toggle
- SessionsRoot (private) [131-160] — Session list/detail navigation
- SessionListScreen (private) [164-257] — Session cards with command buttons
- SessionCard (private) [259-483] — Session info with context usage bar
- SessionDetailScreen (private) [487-710] — Session detail with record list
- RecordItem (internal) [715-727] — Dispatches to type-specific record renderers
- UserRecordBubble (private) [729-768] — User prompt bubble
- AssistantRecordBubble (private) [770-821] — Assistant response bubble
- ToolCallChip (private) [824-858] — Tool invocation chip
- ToolResultChip (private) [860-897] — Tool result with success/failure
- SystemRecord (private) [899-914] — System message text

HELPERS:
- formatModelName [918-930] — Abbreviates model names
- formatActivityTime [932-957] — Formats ISO timestamps as relative time

**Should extract:** SessionCard, all record bubbles/chips, formatters

---

### SendScreen.kt — 563 lines
**3 composables, 2 helpers**

- SendScreen (public) [80-429] — Compose message form
- DepartmentPickerDialog (private) [431-475] — Department selection dialog
- ThreadPicker (private) [477-537] — Thread dropdown picker
- threadAgeMs [539-547] — Calculate thread age
- formatThreadAge [549-562] — Format thread age

**Should extract:** DepartmentPickerDialog (already have DepartmentPicker component — duplicate?), ThreadPicker

---

### SettingsScreen.kt — 528 lines
**8 composables, 1 helper**

- SettingsScreen (public) [43-100] — Main settings container
- UpdateCard (private) [102-149] — System update status + install
- TokenCard (private) [170-194] — FCM token display/copy
- VoiceMapCard (private) [196-314] — Voice assignments per department
- VoicePickerDialog (private) [316-372] — Voice selection dialog
- VoiceModelCard (private) [374-425] — TTS model status
- VoiceSettingsCard (private) [427-463] — TTS playback speed
- StreamDiagnosticCard (private) [465-527] — GPU TTS connection test
- installApk [151-168] — APK install helper

**Could extract:** VoicePickerDialog, settings cards (pattern)

---

### HistoryScreen.kt — 485 lines
**2 composables, 1 helper**

- HistoryScreen (public) [82-314] — Paginated message archive with search
- HistoryCard (private) [317-437] — History message card with copy/replay
- formatHistoryTime [443-484] — Time formatter

**Should extract:** HistoryCard

---

### BoardScreen.kt — 437 lines
**4 composables, 4 helpers**

- BoardScreen (public) [58-243] — Whiteboard tasks grouped by status
- SectionHeader (private) [247-268] — Section header with count
- TaskCard (private) [270-370] — Task card with status/assignee
- AssigneeBadge (private) [372-386] — Department badge
- statusColor [390-396] — Status to color
- statusIcon [398-404] — Status to icon
- assigneeColor [406-415] — Assignee to color
- formatTaskAge [418-436] — Relative age

**Should extract:** TaskCard, AssigneeBadge, status helpers (shared utility)

---

### PulseScreen.kt — 334 lines
**3 composables, 2 helpers**

- PulseScreen (public) [58-179] — Activity broadcast feed
- PulsePostCard (private) [181-264] — Single pulse post
- TagBadge (private) [266-291] — Tag badge
- channelColor [296-308] — Channel to color
- formatPulseTime [313-333] — Relative time

**Should extract:** PulsePostCard, TagBadge

---

### EventFeedScreen.kt — 322 lines
**2 composables, 3 helpers**

- EventFeedScreen (public) [79-181] — Real-time event feed with filtering
- EventCard (private) [183-254] — Single event card
- eventVisuals [259-272] — Event type to icon/color
- eventLabel [277-291] — Event type to label
- formatEventTime [296-321] — Relative time

---

### LiveSessionScreen.kt — 332 lines
**1 composable**

- LiveSessionScreen (public) [52-331] — Live session activity viewer

---

### GeminiWorkspaceScreen.kt — 259 lines
**5 composables**

- GeminiWorkspaceScreen (public) [32-50] — Gemini session management
- SessionListContent (private) [52-80] — Session list
- SessionRow (private) [82-106] — Session list row
- SessionDetailContent (private) [108-211] — Active session messages + input
- GeminiMessageBubble (private) [213-258] — Message bubble with thoughts

---

### ChatScreen.kt — 162 lines
**2 composables, 1 helper — smallest file, cleanest**

- ChatScreen (public) [29-99] — Session list with compose FAB
- SessionRow (private) [101-148] — Session row with status
- formatTimestamp [150-161] — HH:mm formatter

---

## Components Folder (What Actually Got Extracted)

Only 3 components were extracted:
- **AgentAvatar.kt** (109 lines) — Colored circle with initials from name hash
- **DepartmentPicker.kt** — Department selection component
- **MiniPlayerBar.kt** — Persistent audio playback overlay

---

## What Should Be Extracted (Priority Order)

### HIGH — Reused across multiple screens
1. **MessageFormatter** — used in MessagesScreen, could be used in HistoryScreen, GeminiWorkspace
2. **InputBar** — should be its own component with callbacks for send, attach, emoji, mic, image
3. **Bubble types** — NigelBubble, AgentBubble, DispatchBubble, ToolBubble, StreamingBubble — all reusable
4. **Time formatters** — 6 different formatTimestamp/formatTime functions across screens, should be ONE shared utility
5. **JumpToBottom** — used in MessagesScreen, useful in any scrollable list

### MEDIUM — Would clean up their parent screens
6. **SessionCard** (from ActivityScreen) — 224 lines of just that one card
7. **TaskCard** (from BoardScreen)
8. **HistoryCard** (from HistoryScreen)
9. **PulsePostCard** (from PulseScreen)
10. **Record bubbles** (UserRecordBubble, AssistantRecordBubble, ToolCallChip, ToolResultChip)

### LOW — Fine where they are for now
11. Settings cards (tightly coupled to SettingsScreen)
12. Gemini components (only used in one screen)

---

## Duplicate/Redundant Code

- **6 different time formatting functions** — formatTimestamp, formatHistoryTime, formatPulseTime, formatEventTime, formatActivityTime, formatTaskAge — all doing roughly the same thing (ISO → relative/formatted time)
- **DepartmentPickerDialog in SendScreen** vs **DepartmentPicker in components/** — possible overlap
- **SessionRow in ChatScreen** vs **SessionRow in GeminiWorkspaceScreen** — same name, similar purpose
- **Multiple bubble patterns** — MessagesScreen and ActivityScreen both have user/assistant bubbles with different implementations

---

## ViewModels Inventory (13 ViewModels)

### MessagesViewModel.kt — 400 lines — LARGEST VIEWMODEL
- **Injected:** ChatBubbleRepository, AudioStreamClient, SessionRepository
- **State:** bubbles, isLoading, isLoadingEarlier, hasEarlier, playingSequence, isSending, isStreaming, streamingText, streamingToolStatus
- **Functions:** loadSession, refresh, loadEarlier, catchUp, replayDispatch, sendStreaming

### ThreadsViewModel.kt — 153 lines
- **Injected:** CmailRepository, GeminiRepository, CmailEventBus
- **State:** threads, departments, currentThread, isLoading, error, agentThoughts
- **Functions:** loadDepartments, refreshThreads, loadThreadDetail, replyToThread

### SendViewModel.kt — 239 lines
- **Injected:** CmailRepository, GeminiRepository, FileTransferClient, CmailOutboxRepository, VoiceNotificationRepository
- **State:** departments, recentThreads, isSending, statusText, error
- **Functions:** loadDepartments, loadRecentThreads, send

### GeminiViewModel.kt — 194 lines
- **Injected:** GeminiRepository, MessageRepository, AudioStreamClient
- **State:** sessions, isLoading, activeSession, agentThoughts
- **Functions:** refreshSessions, loadSession, clearActiveSession, sendMessage

### LiveSessionViewModel.kt — 190 lines
- **Injected:** SessionRepository
- **State:** sessionInfo, records, discovering, discoveryError, pollError
- **Functions:** startDiscovery, stopPolling

### HistoryViewModel.kt — 135 lines
- **Injected:** HistoryRepository, FileTransferClient, AudioStreamClient
- **State:** history, totalMessages, knownSenders, isLoading, isLoadingMore, error
- **Functions:** loadMessages, setSearch, setSender, replayMessage, downloadFile

### AgentsViewModel.kt — 132 lines
- **Injected:** SessionRepository, EventRepository
- **State:** sessions, totalSessions, events, totalEvents, isLoading, error
- **Functions:** refreshAll, refreshSessions, setEventFilter, refreshEvents, sendSessionCommand

### PulseViewModel.kt — 103 lines
- **Injected:** PulseRepository
- **State:** posts, totalPosts, channels, isLoading, error
- **Functions:** refreshAll, setChannel, refreshPosts

### SettingsViewModel.kt — 100 lines
- **Injected:** ConfigRepository, FileTransferClient
- **State:** voiceMap, connectionStatus, updateStatus, isLoading
- **Functions:** refreshVoiceMap, updateVoiceAssignment, testConnection, downloadAndInstallUpdate

### DebugViewModel.kt — 102 lines
- **Injected:** DebugRepository
- **State:** hookRouterLogs, cmailHooksLogs, activeSessions, logFiles, serverHealth, isLoading
- **Functions:** loadTab

### SessionDetailViewModel.kt — 95 lines
- **Injected:** SessionRepository
- **State:** sessionDetail, records, isLoading, error
- **Functions:** loadSession, startLiveWatching, stopPolling

### ChatViewModel.kt — 85 lines
- **Injected:** SessionRepository
- **State:** sessions, sessionInfoList, isRefreshing, selectedSessionId
- **Functions:** selectSession, refresh

### BoardViewModel.kt — 53 lines
- **Injected:** WhiteboardRepository
- **State:** board, isLoading, error
- **Functions:** refreshBoard

---

## Components Folder Detail (3 files)

### AgentAvatar.kt — 110 lines
- AgentAvatar (public) — Colored circle with initials from name hash
- getAgentIdentity (public) — Maps name to AgentIdentity (initials + color)

### DepartmentPicker.kt — 105 lines
- DepartmentPicker (public) — Multi-select grid for choosing departments
- DepartmentChip (private) — Individual selectable department chip

### MiniPlayerBar.kt — 153 lines
- MiniPlayerBar (public) — Slide-up audio player bar with avatar, play/pause, skip

---

## Full Numbers

| Layer | Files | Lines | Composables | Helpers |
|-------|-------|-------|-------------|---------|
| Screens | 11 | 5,063 | 48 | 16 |
| Components | 3 | 368 | 5 | 1 |
| ViewModels | 13 | 2,081 | 0 | 0 |
| **Total** | **27** | **7,512** | **53** | **17** |

**Ratio of extracted components to total composables: 5 out of 53 (9.4%)**

90% of the UI is trapped inside screen files as private functions.
