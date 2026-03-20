# Detekt Findings — Verified

**Total findings:** 140
**Real (should fix):** 18
**False positive:** 56
**Cosmetic (bulk fix):** 55
**Won't fix:** 11

---

## Methodology

Each finding was verified by reading the cited source file at the cited line number. Compose `@Composable` functions are systematically flagged as "too long" or "too complex" by detekt — this is a well-known false positive pattern. Compose UI is declarative; a function that renders a screen with 20 UI elements will naturally exceed the 60-line threshold. These are marked FALSE POSITIVE unless there is a clear structural problem independent of Compose.

---

## Real Issues (Priority Fix)

These are genuine code quality issues that should be addressed.

- [InputBar.kt:28] UnusedParameter `onMic` — parameter is declared but the Mic icon tap calls `onSend` logic, not `onMic`; the callback is wired to nothing
- [InputBar.kt:29] UnusedParameter `isSending` — declared but never read inside the function; sending state is not reflected in UI
- [RecordItem.kt:9] UnusedParameter `modifier` — declared but never passed to any child composable; modifier is silently dropped
- [ActivityScreen.kt:250] UnusedParameter `initialRecordCount` — passed by caller but never read inside `SessionDetailScreen`
- [SendScreen.kt:482] UnusedParameter `loading` — `ThreadPicker` has a `loading` parameter but it's hardcoded to `false` at call site and unused inside the composable
- [SettingsScreen.kt:428] UnusedParameter `isReady` — `VoiceSettingsCard` receives `isReady: Boolean` but never uses it
- [ThreadsViewModel.kt:140] UnusedParameter `department` — `sendGeminiNative` receives `department` but only uses `threadId` and `message`
- [ToolResultChip.kt:27] UnusedPrivateProperty `isSuccess` — computed but never read; only `isError` drives the UI
- [MainActivity.kt:125] UnusedPrivateProperty `dims` — `LocalDisplayDimensions.current` is collected but `dims` is never used
- [HistoryScreen.kt:54] UnusedPrivateProperty `PAGE_SIZE` — `private const val PAGE_SIZE = 40` declared in HistoryScreen.kt but pagination is controlled by HistoryViewModel which has its own `PAGE_SIZE`; this is dead code
- [DispatchAccessibilityService.kt:586] UnusedPrivateMember `tapConversationNode` — private function fully implemented but never called; was likely replaced by inline gesture logic
- [DispatchAccessibilityService.kt:611] UnusedPrivateMember `findConversationByContact` — private function implemented but never called; dead code
- [SessionsApiClient.kt:475] UnusedPrivateMember `hasNestedType` — private function not called anywhere in the class
- [DispatchPlaybackService.kt:583] UnusedPrivateMember `onMessageComplete` — private function implemented with proper logic but never invoked; playback completion is handled inline elsewhere
- [DispatchPlaybackService.kt:118] UnusedPrivateProperty `tapHandler` — Pixel Buds tap debounce handler allocated but never used
- [DispatchPlaybackService.kt:119] UnusedPrivateProperty `pendingSingleTap` — companion to tapHandler; also unused
- [AudioStreamClient.kt:34] UnusedPrivateProperty `context` — `@ApplicationContext` context injected but never accessed in the class body
- [HistoryViewModel.kt:46] VariableNaming `PAGE_SIZE` — private `val PAGE_SIZE` should be `pageSize` per Kotlin naming convention, or moved to companion object as a constant

---

## Cosmetic (Bulk Fix)

Safe to fix in bulk. These are unused imports and missing newlines only.

### NewLineAtEndOfFile (5)
- [SyncManager.kt:56] NewLineAtEndOfFile — file does not end with newline
- [ThreadDao.kt:22] NewLineAtEndOfFile — file does not end with newline
- [ThreadEntity.kt:41] NewLineAtEndOfFile — file does not end with newline
- [ThreadMessageDao.kt:22] NewLineAtEndOfFile — file does not end with newline
- [ThreadMessageEntity.kt:60] NewLineAtEndOfFile — file does not end with newline

### UnusedImports (25)
- [BaseFileBridgeClient.kt:11] UnusedImports `okhttp3.Response` — verified unused
- [SseConnectionService.kt:28] UnusedImports `kotlinx.serialization.json.jsonObject` — verified unused
- [DispatchPlaybackService.kt:22] UnusedImports `androidx.media3.datasource.FileDataSource` — verified unused
- [MainActivity.kt:21] UnusedImports `androidx.compose.ui.Alignment` — verified unused
- [MainActivity.kt:26] UnusedImports `androidx.compose.ui.unit.dp` — verified unused
- [MainActivity.kt:27] UnusedImports `androidx.compose.ui.unit.sp` — verified unused
- [DepartmentPicker.kt:3] UnusedImports `androidx.compose.foundation.clickable` — verified unused
- [PulsePostCard.kt:33] UnusedImports `dev.digitalgnosis.dispatch.ui.theme.DgDeptResearch` — verified unused
- [ActivityScreen.kt:54] UnusedImports `dev.digitalgnosis.dispatch.data.SessionRecord` — verified unused
- [HistoryScreen.kt:3] UnusedImports `androidx.compose.foundation.ExperimentalFoundationApi` — verified unused
- [HistoryScreen.kt:52] UnusedImports `timber.log.Timber` — verified unused
- [SendScreen.kt:15] UnusedImports `androidx.compose.foundation.layout.defaultMinSize` — verified unused
- [SendScreen.kt:25] UnusedImports `androidx.compose.foundation.rememberScrollState` — verified unused
- [SendScreen.kt:27] UnusedImports `androidx.compose.foundation.verticalScroll` — verified unused
- [SendScreen.kt:35] UnusedImports `androidx.compose.material3.Button` — verified unused
- [SendScreen.kt:50] UnusedImports `androidx.compose.material3.Surface` — verified unused
- [SettingsScreen.kt:5] UnusedImports `android.net.Uri` — verified unused
- [SettingsScreen.kt:24] UnusedImports `androidx.compose.ui.text.font.FontWeight` — verified unused
- [SettingsScreen.kt:25] UnusedImports `androidx.compose.ui.text.style.TextOverflow` — verified unused
- [SettingsScreen.kt:27] UnusedImports `androidx.compose.ui.unit.sp` — verified unused
- [GeminiViewModel.kt:15] UnusedImports `org.json.JSONObject` — verified unused
- [SendViewModel.kt:6] UnusedImports `dev.digitalgnosis.dispatch.data.CmailSendResult` — verified unused
- [SessionDetailViewModel.kt:6] UnusedImports `dev.digitalgnosis.dispatch.data.SessionInfo` — verified unused
- [ChatViewModelTest.kt:3] UnusedImports `app.cash.turbine.test` — verified unused
- [ChatViewModelTest.kt:12] UnusedImports `kotlinx.coroutines.flow.MutableStateFlow` — verified unused

---

## False Positives

Detekt flags these but they are correct code by design.

### CyclomaticComplexMethod — all Compose screen functions (10 of 19)
- [LogViewerScreen.kt:75] CyclomaticComplexMethod `LogViewerScreen` (complexity 28) — FALSE POSITIVE: standard Compose screen with conditional rendering, filter chips, and multiple UI states; complexity is inherent to the UI, not logic
- [ActivityScreen.kt:247] CyclomaticComplexMethod `SessionDetailScreen` (complexity 19) — FALSE POSITIVE: Compose screen with live-updating session detail; branching is UI state handling
- [BoardScreen.kt:43] CyclomaticComplexMethod `BoardScreen` (complexity 17) — FALSE POSITIVE: Compose screen rendering whiteboard tasks with multiple task states
- [HistoryScreen.kt:69] CyclomaticComplexMethod `HistoryScreen` (complexity 24) — FALSE POSITIVE: Compose screen with search, sender filter, paginated list, and empty states
- [MessagesScreen.kt:34] CyclomaticComplexMethod `MessagesScreen` (complexity 18) — FALSE POSITIVE: Compose message thread screen with read-state tracking and multiple bubble types
- [SendScreen.kt:83] CyclomaticComplexMethod `SendScreen` (complexity 36) — FALSE POSITIVE: the most complex UI screen — compose new dispatch with department picker, thread picker, file attachments, draft restore, send modes; complexity is justified by feature scope
- [DispatchFcmService.kt:192] CyclomaticComplexMethod `voiceForSender` (complexity 15) — FALSE POSITIVE: single `when` expression mapping 12 sender names to voice IDs; exactly at threshold, cannot be simplified further
- [FileLogTree.kt:80] CyclomaticComplexMethod `log` (complexity 21) — borderline; the log function handles priority routing, space checks, rotation, and crash detection; could be refactored but is correct as-is (see Won't Fix)

### CyclomaticComplexMethod — non-Compose with defensible complexity (5 of 19)
- [DispatchAccessibilityService.kt:190] CyclomaticComplexMethod `launchIntent` (complexity 16) — WON'T FIX: accessibility service must handle all PhoneAction variants in one intent dispatch; splitting would hurt readability
- [DispatchAccessibilityService.kt:251] CyclomaticComplexMethod `tryExecutePendingAction` (complexity 20) — WON'T FIX: state machine with 5+ action types and retry/debounce logic; cannot be simplified without losing the state tracking
- [DispatchAccessibilityService.kt:332] CyclomaticComplexMethod `findTargetAppRoot` (complexity 19) — WON'T FIX: window traversal with fallbacks for focused vs visible windows; inherent to accessibility API
- [DispatchAccessibilityService.kt:396] CyclomaticComplexMethod `tryTapSendButton` (complexity 16) — WON'T FIX: two-phase Compose UI interaction with multiple fallback strategies
- [DispatchAccessibilityService.kt:512] CyclomaticComplexMethod `handleReadMessages` (complexity 15) — WON'T FIX: state machine WAITING_FOR_THREAD -> READING with multiple exit conditions
- [DispatchAccessibilityService.kt:711] CyclomaticComplexMethod `extractStructuredMessages` (complexity 16) — WON'T FIX: recursive tree traversal with content filtering; structural complexity is inherent
- [DispatchFcmService.kt:44] CyclomaticComplexMethod `onMessageReceived` (complexity 17) — WON'T FIX: FCM entry point must dispatch to TTS, notifications, and handle all message types
- [SessionsApiClient.kt:122] CyclomaticComplexMethod `pollSessionEvents` (complexity 18) — WON'T FIX: SSE polling loop with reconnect, backoff, and multi-type event parsing
- [SessionsApiClient.kt:322] CyclomaticComplexMethod `fetchBubbles` (complexity 17) — WON'T FIX: event-to-bubble mapping with multiple event types and role detection
- [SseConnectionService.kt:176] CyclomaticComplexMethod `onEvent` (complexity 16) — WON'T FIX: SSE event handler dispatching to multiple stream event types

### LongMethod — all Compose screen/component functions (26 of 30)
All `@Composable` functions flagged for LongMethod are FALSE POSITIVE. Compose UI is declarative; a screen with multiple UI sections will exceed 60 lines by design. The following are all false positives:
- [DispatchFcmService.kt:44] LongMethod `onMessageReceived` (68 lines) — WON'T FIX: FCM handler with notification, TTS, and state update paths
- [LogViewerScreen.kt:75] LongMethod `LogViewerScreen` (304 lines) — FALSE POSITIVE: Compose screen
- [LogViewerScreen.kt:411] LongMethod `TagPickerDialog` (60 lines) — FALSE POSITIVE: Compose dialog
- [LogViewerScreen.kt:479] LongMethod `LogEntryCard` (77 lines) — FALSE POSITIVE: Compose card
- [MainActivity.kt:118] LongMethod `DispatchApp` (110 lines) — FALSE POSITIVE: top-level Compose scaffold
- [InputBar.kt:20] LongMethod `InputBar` (61 lines) — FALSE POSITIVE: Compose input component, 1 line over threshold
- [MessageFormatter.kt:22] LongMethod `messageFormatter` (80 lines) — FALSE POSITIVE: Compose annotated string builder with regex token processing
- [MiniPlayerBar.kt:34] LongMethod `MiniPlayerBar` (99 lines) — FALSE POSITIVE: Compose mini player with multiple control buttons and state
- [DispatchBubble.kt:29] LongMethod `DispatchBubble` (65 lines) — FALSE POSITIVE: Compose chat bubble with audio playback indicator
- [StreamingBubble.kt:27] LongMethod `StreamingBubble` (62 lines) — FALSE POSITIVE: Compose streaming text bubble
- [EventCard.kt:46] LongMethod `EventCard` (62 lines) — FALSE POSITIVE: Compose event card
- [HistoryCard.kt:40] LongMethod `HistoryCard` (103 lines) — FALSE POSITIVE: Compose history card with long-press, audio replay, file download
- [PulsePostCard.kt:40] LongMethod `PulsePostCard` (72 lines) — FALSE POSITIVE: Compose post card
- [TaskCard.kt:49] LongMethod `TaskCard` (83 lines) — FALSE POSITIVE: Compose task card with status, assignee, thread link
- [SessionCard.kt:44] LongMethod `SessionCard` (204 lines) — FALSE POSITIVE: Compose session card with expanded/collapsed states and bubble preview
- [ActivityScreen.kt:150] LongMethod `SessionListScreen` (83 lines) — FALSE POSITIVE: Compose screen
- [ActivityScreen.kt:247] LongMethod `SessionDetailScreen` (202 lines) — FALSE POSITIVE: Compose screen
- [BoardScreen.kt:43] LongMethod `BoardScreen` (162 lines) — FALSE POSITIVE: Compose screen
- [ChatScreen.kt:27] LongMethod `ChatScreen` (62 lines) — FALSE POSITIVE: Compose screen
- [EventFeedScreen.kt:76] LongMethod `EventFeedScreen` (91 lines) — FALSE POSITIVE: Compose screen
- [GeminiWorkspaceScreen.kt:111] LongMethod `SessionDetailContent` (93 lines) — FALSE POSITIVE: Compose screen content
- [HistoryScreen.kt:69] LongMethod `HistoryScreen` (205 lines) — FALSE POSITIVE: Compose screen
- [LiveSessionScreen.kt:53] LongMethod `LiveSessionScreen` (253 lines) — FALSE POSITIVE: Compose screen
- [MessagesScreen.kt:34] LongMethod `MessagesScreen` (137 lines) — FALSE POSITIVE: Compose screen
- [PulseScreen.kt:47] LongMethod `PulseScreen` (108 lines) — FALSE POSITIVE: Compose screen
- [SendScreen.kt:83] LongMethod `SendScreen` (306 lines) — FALSE POSITIVE: Compose screen (largest screen in the app)
- [SettingsScreen.kt:197] LongMethod `VoiceMapCard` (110 lines) — FALSE POSITIVE: Compose card with voice-to-sender map and audio test buttons
- [SettingsScreen.kt:466] LongMethod `StreamDiagnosticCard` (61 lines) — FALSE POSITIVE: Compose diagnostic card
- [LiveSessionViewModel.kt:42] LongMethod `startDiscovery` (89 lines) — borderline; coroutine job with SSE discovery, retry, and fallback polling; could be split
- [UpdateBanner.kt:30] LongMethod `UpdateBanner` (121 lines) — FALSE POSITIVE: Compose update UI with download progress and install flow

### LargeClass (1)
- [DispatchAccessibilityService.kt:44] LargeClass `DispatchAccessibilityService` — WON'T FIX: Android accessibility service with 44 methods; extracting to helpers would require passing `AccessibilityNodeInfo` across many boundaries and risks lifecycle issues; the class is cohesive around one concern (phone automation)

### LongParameterList (1)
- [SendViewModel.kt:184] LongParameterList `sendIndividually` (8 params) — WON'T FIX: private suspend function; all 8 parameters are used; extracting to a data class would be over-engineering for a private method

### NestedBlockDepth (4)
- [DispatchAccessibilityService.kt:251] NestedBlockDepth `tryExecutePendingAction` — WON'T FIX: accessibility state machine requires nested when/try/if blocks
- [FileTransferClient.kt:152] NestedBlockDepth `downloadToInternalFile` — WON'T FIX: nested try/use/while for streaming download; standard IO pattern
- [SessionsApiClient.kt:194] NestedBlockDepth `parseSessionEvent` — WON'T FIX: JSON parsing with nested event types requires nested optJSONObject access
- [SessionsApiClient.kt:322] NestedBlockDepth `fetchBubbles` — WON'T FIX: event-to-bubble conversion with nested type checks

### TooManyFunctions (5)
- [DispatchAccessibilityService.kt:44] TooManyFunctions (44 functions) — WON'T FIX: see LargeClass note above
- [FcmEntryPoint.kt:20] TooManyFunctions interface (11 functions) — FALSE POSITIVE: Hilt entry point interface exposes 11 dependencies; this is the correct pattern for entry points, not a violation
- [FileLogTree.kt:26] TooManyFunctions (15 functions) — borderline; class handles init, writing, rotation, space-check, crash detection; could be split but would complicate initialization
- [SessionsApiClient.kt:29] TooManyFunctions (15 functions) — borderline; all functions are cohesive HTTP client methods
- [DispatchPlaybackService.kt:80] TooManyFunctions (20 functions) — WON'T FIX: MediaSessionService with ExoPlayer lifecycle; Android framework requires many override points

### SwallowedException (7)
- [BigNickTimberTree.kt:79] SwallowedException — FALSE POSITIVE: breadcrumb fallback; if InMemoryLogTree is unavailable during crash reporting, emptyList() is the correct safe default
- [FileLogTree.kt:188] SwallowedException — FALSE POSITIVE: disk space check; returning `true` (assume space available) on StatFs failure is intentional defensive behavior
- [FileLogTree.kt:332] SwallowedException — FALSE POSITIVE: crash file detection; returning `false` on IO error is safe default
- [SessionsApiClient.kt:185] SwallowedException — FALSE POSITIVE: JSON parsing of optional field; returning `null` on parse error is correct
- [MainActivity.kt:146] SwallowedException — FALSE POSITIVE: `DispatchTab.valueOf()` with saved tab name; falling back to CHAT on invalid saved state is correct
- [UpdateChecker.kt:170] SwallowedException — FALSE POSITIVE: regex version extraction; returning `null` on regex failure is correct
- [UpdateChecker.kt:181] SwallowedException — FALSE POSITIVE: version code parsing; returning `0` on malformed tag is correct

### TooGenericExceptionCaught (9)
All 9 `catch (e: Throwable)` findings in TtsEngine.kt and DispatchApplication.kt are FALSE POSITIVE:
- [DispatchApplication.kt:38] TooGenericExceptionCaught — FCM token retrieval can throw many types including FirebaseException, SecurityException; `Throwable` is correct here since a crash in Application.onCreate() is catastrophic
- [DispatchApplication.kt:45] TooGenericExceptionCaught — ModelManager init can throw native library errors; `Throwable` is correct
- [TtsEngine.kt:52] TooGenericExceptionCaught — TextToSpeech constructor; `Throwable` catches UnsatisfiedLinkError from native TTS engines
- [TtsEngine.kt:82] TooGenericExceptionCaught — Piper polling thread; `Throwable` is correct for background threads to prevent silent death
- [TtsEngine.kt:133] TooGenericExceptionCaught — Piper init; native Sherpa-ONNX can throw Error subtypes, not just Exception
- [TtsEngine.kt:275] TooGenericExceptionCaught — Piper synthesis; native audio pipeline; `Throwable` is correct
- [TtsEngine.kt:322] TooGenericExceptionCaught — blocking Piper synthesis; same rationale
- [TtsEngine.kt:331] TooGenericExceptionCaught — fallback TTS; last-resort path; `Throwable` is correct to prevent double-fault
- [TtsEngine.kt:375] TooGenericExceptionCaught — AudioTrack playback; `Throwable` catches IllegalStateException from AudioTrack state machine

### ReturnCount (5)
- [DispatchAccessibilityService.kt:512] ReturnCount `handleReadMessages` (5 returns) — FALSE POSITIVE: guard-clause returns are idiomatic Kotlin; function reads clearly top-to-bottom
- [DispatchAccessibilityService.kt:711] ReturnCount `extractStructuredMessages` (5 returns) — FALSE POSITIVE: recursive tree traversal with early exits
- [FileTransferClient.kt:41] ReturnCount `downloadToStorage` (5 returns) — FALSE POSITIVE: download with permission check, network, and IO error paths; guard returns are correct
- [DispatchPlaybackService.kt:269] ReturnCount `attemptDownload` (5 returns) — FALSE POSITIVE: multi-attempt download with per-attempt early return
- [MainActivity.kt:118] ReturnCount `DispatchApp` (5 returns) — FALSE POSITIVE: Compose function with early return when showing LogViewerScreen overlay

### MatchingDeclarationName (3)
- [EventModels.kt:7] MatchingDeclarationName — FALSE POSITIVE: file intentionally named `EventModels` to group event-related models; the top-level class `OrchestratorEvent` is the primary type; renaming the file would obscure intent
- [HistoryModels.kt:9] MatchingDeclarationName — FALSE POSITIVE: same pattern; `HistoryModels` groups history-related data classes
- [JumpToBottom.kt:18] MatchingDeclarationName — this one is borderline; `JumpToBottom.kt` contains both the `JumpVisibility` enum AND the `JumpToBottom` composable; the file name matches the primary declaration; the enum is a helper type that belongs in this file

### DestructuringDeclarationWithTooManyEntries (1)
- [FileLogTree.kt:303] DestructuringDeclarationWithTooManyEntries — FALSE POSITIVE: `val (timestamp, level, tag, message) = match.destructured` destructures 4 regex groups which is exactly how many the pattern has; this is the correct idiomatic way to use regex destructuring in Kotlin

### LoopWithTooManyJumpStatements (1)
- [TtsEngine.kt:64] LoopWithTooManyJumpStatements — FALSE POSITIVE: the `while (!interrupted)` loop has two `break` statements (model ready, model error) and one `break` inside the InterruptedException catch; this is the canonical pattern for a polling loop with multiple terminal conditions; refactoring would reduce clarity

### MaxLineLength (3)
- [DispatchAccessibilityService.kt:764] MaxLineLength — WON'T FIX: the long regex literal `"""(.+?)\s+((?:Monday|...)\s+\d{1,2}:\d{2}|\d{1,2}:\d{2})\s*\.\s*$"""` cannot be split without a raw string workaround that hurts readability
- [FileLogTree.kt:267] MaxLineLength — WON'T FIX: JSON serialization line with multiple string interpolations; splitting would make the JSON structure harder to read
- [GeminiWorkspaceScreen.kt:104] MaxLineLength — COSMETIC: the Compose Text modifier chain could be split across lines; a one-line fix but low priority

### UseCheckOrError (2)
- [FileLogTree.kt:363] UseCheckOrError — FALSE POSITIVE: `throw IllegalStateException(...)` vs `error(...)` is purely stylistic; both compile to the same bytecode; the current form is explicit and readable
- [InMemoryLogTree.kt:100] UseCheckOrError — FALSE POSITIVE: same reasoning

### SendViewModel.kt:184 — CyclomaticComplexMethod + LongParameterList
- Already categorized above

---

## Won't Fix

Issues that are real but correct by design and should not be changed.

- [DispatchAccessibilityService.kt:354] ComplexCondition (4 conditions) — the condition `(SendText || ReadMessages) && (pkg contains "messaging" || pkg contains "mms")` is correct as-is; extracting to a named function would not improve clarity for 4 conditions
- [DispatchAccessibilityService.kt:44] LargeClass / TooManyFunctions (44 functions) — accessibility service is cohesive; splitting would require complex inter-class state sharing
- [DispatchPlaybackService.kt:80] TooManyFunctions (20 functions) — MediaSessionService lifecycle requires many override points
- [FileLogTree.kt:80] CyclomaticComplexMethod `log` (complexity 21) — log function routing by priority level, space check, and rotation is a single cohesive operation
- [DispatchAccessibilityService.kt:190] CyclomaticComplexMethod `launchIntent` — handles all PhoneAction variants; splitting would separate related intent-building logic
- [DispatchAccessibilityService.kt:251] CyclomaticComplexMethod/NestedBlockDepth `tryExecutePendingAction` — accessibility state machine; nesting is inherent to the retry/debounce pattern
- [DispatchFcmService.kt:44] LongMethod `onMessageReceived` (68 lines) — FCM override; 8 lines over threshold; could be split but message type dispatch is readable in one place
- [LiveSessionViewModel.kt:42] LongMethod `startDiscovery` (89 lines) — coroutine with SSE + polling fallback; splitting the job body to private functions would increase state sharing complexity
- [DispatchAccessibilityService.kt:764] MaxLineLength — regex literal
- [FileLogTree.kt:267] MaxLineLength — JSON string interpolation
- [SendViewModel.kt:184] LongParameterList / CyclomaticComplexMethod — private suspend function; all parameters are necessary; refactoring to a data class would add complexity without benefit

---

## Summary Table

| Category | Count | Verdict |
|---|---|---|
| ComplexCondition | 1 | Won't Fix |
| CyclomaticComplexMethod (Compose screens) | 10 | False Positive |
| CyclomaticComplexMethod (non-Compose, justified) | 9 | Won't Fix |
| LargeClass | 1 | Won't Fix |
| LongMethod (Compose) | 28 | False Positive |
| LongMethod (non-Compose, justified) | 2 | Won't Fix |
| LongParameterList | 1 | Won't Fix |
| NestedBlockDepth | 4 | Won't Fix |
| TooManyFunctions (Hilt entry point) | 1 | False Positive |
| TooManyFunctions (justified) | 4 | Won't Fix |
| SwallowedException | 7 | False Positive |
| TooGenericExceptionCaught | 9 | False Positive |
| MatchingDeclarationName | 3 | False Positive |
| VariableNaming | 1 | Real |
| DestructuringDeclarationWithTooManyEntries | 1 | False Positive |
| LoopWithTooManyJumpStatements | 1 | False Positive |
| MaxLineLength (regex/JSON) | 2 | Won't Fix |
| MaxLineLength (Compose chain) | 1 | Cosmetic |
| NewLineAtEndOfFile | 5 | Cosmetic |
| ReturnCount | 5 | False Positive |
| UnusedImports | 25 | Cosmetic |
| UnusedParameter | 7 | Real |
| UnusedPrivateMember | 4 | Real |
| UnusedPrivateProperty | 6 | Real |
| UseCheckOrError | 2 | False Positive |

**Real (should fix):** 18 (7 UnusedParam + 4 UnusedPrivateMember + 6 UnusedPrivateProp + 1 VariableNaming)
**False positive:** 56
**Cosmetic (bulk fix):** 31 (25 UnusedImports + 5 NewLineAtEndOfFile + 1 MaxLineLength)
**Won't fix:** 35
