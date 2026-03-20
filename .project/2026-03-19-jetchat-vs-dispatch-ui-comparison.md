# Jetchat vs Dispatch UI Comparison
**Date:** 2026-03-19
**Purpose:** Identify gaps in Dispatch's messaging UI against Google's reference implementation, and recommend what to adopt vs. what to keep.

---

## 1. Chat Bubble Layout and Styling

### Jetchat
- Uses a single, consistent `ChatItemBubble` composable for all messages, regardless of sender.
- "You" bubbles use `primary` as the background color; other-user bubbles use `surfaceVariant`. Both are pulled from the Material3 color scheme — no hard-coded colors.
- Bubble shape is asymmetric: the upper-left corner is nearly square (4dp) and the other three corners are fully rounded (20dp). This creates the classic "tail" effect without a separate tail element.
- Author avatar (42dp circular) and name appear only on the first message in a run from a given sender, not on every bubble. Subsequent bubbles from the same sender use an equivalent blank spacer so the content column stays aligned.
- Author name and timestamp sit on the same baseline-aligned row above the first bubble. They are grouped into a single accessibility node.
- Between bubbles from the same sender: 4dp gap. Between runs from different senders: 8dp top padding on the row.
- Images appear in a second bubble surface directly below the text bubble, sharing the same color and shape.

### Dispatch (Current)
- Has four distinct composables for bubble types: `NigelBubble` (user), `AgentBubble` (AI), `DispatchBubble` (audio-playback messages), and `ToolBubble` (tool status). Each is a private composable in MessagesScreen.kt.
- Colors are hard-coded literals in each composable: `0xFF0842A0` for Nigel, `0xFF282A2C` for Agent, etc. They are not pulled from the active color scheme token for most bubbles, though `DgDispatchBubble` and `DgToolBubble` are defined in `Color.kt`.
- Bubble shape also uses the asymmetric pattern: one corner at 4dp, the others at 18–20dp. User bubbles point right (`bottomEnd = 4.dp`); agent bubbles point left (`bottomStart = 4.dp`). This matches the Jetchat convention.
- No avatar is shown inside the message list next to individual bubbles. The `AgentAvatar` component is used only in the header bar.
- Timestamp appears below each individual bubble unconditionally when `bubble.timestamp` is non-blank. In Jetchat, the timestamp is shown only once per sender run.
- `ToolBubble` and the streaming overlay have their own color and shape, making them visually distinct from chat messages — this is appropriate but lacks animation on entry.
- `DispatchBubble` has an inline play/pause button and detail label — functionality Jetchat doesn't need.
- Maximum bubble width is set with `widthIn(max = 360.dp)` for agent/user bubbles and `300.dp` for dispatch bubbles. Jetchat achieves natural width constraint via `weight(1f)` on the content column.

### Gap
- All bubble colors in Dispatch are hard-coded, making them immune to theme switching and Material3 dynamic color. They will look wrong in light mode.
- No avatar-per-run grouping: every agent bubble is visually isolated, with no indication which "person" it's from at the sender-run level.
- Timestamps appear on every bubble rather than once per run, adding visual noise.
- No inter-bubble spacing strategy: bubbles use `Arrangement.spacedBy(2.dp)` uniformly; there's no visual gap between runs from different senders.

### Recommendation
- Move hard-coded bubble colors into the color scheme (`surfaceContainerHigh` already in Dispatch's Color.kt is the right token for agent bubbles in dark mode). Keep `DgDispatchBubble` and `DgToolBubble` for their distinct semantic meaning.
- Add sender-run grouping logic (compare current bubble's sender to the previous one) and show timestamps only on the last bubble of each run.
- Apply extra top padding (8dp) when a new sender run begins, to provide visual breathing room.
- Consider adding a small avatar indicator (the `AgentAvatar`) at the bottom-left of the last bubble in an agent run.

---

## 2. Message List (LazyColumn Setup, Scroll, Date Grouping)

### Jetchat
- Uses `reverseLayout = true` on the `LazyColumn`. Messages are stored newest-first and the list starts at index 0 at the bottom. Scrolling up reveals older messages.
- Sending a message calls `scrollToItem(0)` which immediately jumps to the latest message (bottom of the visual view).
- The `JumpToBottom` button is triggered by a `derivedStateOf` check: it appears when `firstVisibleItemIndex != 0` or the scroll offset exceeds a 56dp threshold.
- Day-separator headers (`DayHeader`) are injected as items between messages using hard-coded index checks in the sample, but the rendering pattern is correct: a `Row` with two `HorizontalDivider` elements flanking a text label, styled with `labelSmall`.
- The list tracks sender runs inline using index comparisons against adjacent messages in the same iteration.

### Dispatch (Current)
- Uses normal layout order (oldest to newest). The list renders in chronological top-to-bottom order.
- Auto-scroll is handled by `LaunchedEffect(bubbles.size, isStreaming)` which calls `animateScrollToItem(bubbles.size - 1)`. This works but auto-scrolls on every size change, including when the user may have manually scrolled up to read earlier messages.
- No `JumpToBottom` button exists. If the user scrolls up during a conversation, there is no one-tap way back to the bottom.
- No date separator headers. All bubbles render without any grouping or temporal anchoring.
- The streaming bubble (`StreamingBubble`) and sending indicator (`SendingIndicator`) are appended as separate list items at the end of the `LazyColumn`. This is functionally sound.
- Pagination is supported (loadEarlier in the ViewModel), but there is no UI trigger at the top of the list to request earlier messages.

### Gap
- Missing `JumpToBottom` — a significant UX omission when messages stream in and the user has scrolled away.
- Auto-scroll fires unconditionally on size changes. This will interrupt a user who is reading earlier history.
- No date dividers make long conversations hard to scan.
- Normal layout order (vs `reverseLayout = true`) means the scroll-to-latest logic requires knowing the last item index, which is more brittle than Jetchat's index-0-is-latest approach.
- No visual or interactive trigger for `loadEarlier` pagination.

### Recommendation
- Add `JumpToBottom` — this is a direct, self-contained lift from Jetchat. Only show it when the user is scrolled away from the bottom.
- Guard the auto-scroll: only animate to bottom when the user was already near the bottom before the new message arrived. Jetchat handles this by coupling scroll-to-bottom to user send actions, not to list size changes.
- Add `DayHeader` separators. Compute them from bubble timestamps by grouping adjacent bubbles sharing the same date.
- Consider whether to keep or migrate to `reverseLayout = true`. Dispatch's model (loading older content on demand) would benefit from it.

---

## 3. Input Bar

### Jetchat
- The input area is a three-layer composable hierarchy: `UserInput` (outer shell, handles keyboard state) → `UserInputText` (text row with animated recording state) + `UserInputSelector` (button row) + `SelectorExpanded` (expandable panel).
- The text field is a `BasicTextField` — fully custom, no visible outline or underline. Placeholder text is a separate `Text` composable shown only when the field is empty and unfocused.
- The input bar has `tonalElevation = 2.dp` and sits in a `Surface`, giving it a subtle lift over the message list.
- When a panel is open (emoji, etc.), the Back button closes the panel rather than navigating away — handled with `BackHandler`.
- The send button is a filled `Button` (shows text "Send") — it disables and shows an outlined variant when there is no text. It is never hidden; it transforms.
- Padding for the navigation bar and IME is applied on the input composable itself so the elevation shadow renders behind the gesture bar.
- Max width of the text field is unconstrained; the text area wraps to multiple lines via `maxLines` (currently hardcoded to 1).

### Dispatch (Current)
- The input row is inline in `MessagesScreen` — a `Row` containing an Add button, a `Surface` with an inner `Row` (emoji icon, `TextField`, photo icon), and an outer send/mic `IconButton`.
- The text field uses Material3 `TextField` with container and indicator colors set to transparent to blend into the `Surface`. This is less composable than `BasicTextField` but achieves the same visual result.
- Send vs. Mic: the rightmost icon toggles between a Send icon (when text is non-blank) and a Mic icon (when blank). Tapping Mic does nothing currently.
- No expandable panels. No emoji selector, no attachment picker panel.
- IME padding is applied on the parent `Column` via `imePadding()`, which means the shadow elevation of the input bar is not independently controlled.
- The "Add" button (plus icon) on the left currently has no functionality.
- No text-send-then-scroll coordination: after sending, the input field clears but there is no explicit scroll-to-bottom call from the input layer.

### Gap
- Dispatch's input is functional but flat — no tonal elevation, no expandable secondary area.
- The Mic button is inert; Jetchat's `RecordButton` provides full gesture-based recording with a swipe-to-cancel idiom.
- No Back-to-close-panel handling (not needed yet, but will be if panels are added).
- The "Add" button and photo button have no implemented behavior.
- No `resetScroll` call after sending.

### Recommendation
- Wrap the input area in a `Surface(tonalElevation = 2.dp)` to lift it visually from the message list.
- Move IME and navigation bar padding to the input surface, not the parent column (matching Jetchat's approach so elevation renders correctly behind gesture nav).
- Implement `resetScroll` (or equivalent) on send so the list jumps to the latest message.
- Defer emoji/attachment panels — but note the architecture needs a `BackHandler` when panels are present.

---

## 4. Voice Input / Record Button

### Jetchat
- `RecordButton` is a dedicated composable with three distinct states: idle (mic icon), recording (expanded with animated red pulse and elapsed timer), and being-cancelled (swipe-left to cancel with opacity fade).
- It uses `detectDragGesturesAfterLongPress` — tap shows a tooltip instructing long-press, long-press starts recording, swipe left cancels.
- During recording, the entire text field is replaced by a `RecordingIndicator` via `AnimatedContent`, showing a pulsing red dot, an elapsed timer, and a "swipe to cancel" hint.
- The button animates: scale grows to 2x with a spring when recording starts, icon color inverts against a filled circle background.
- A `RichTooltip` appears on short tap to educate the user.
- The recording gesture tracks horizontal swipe offset to implement swipe-to-cancel with a 200dp threshold.

### Dispatch (Current)
- The mic icon appears as the send button when the text field is empty. Tapping it does nothing.
- No gesture handling, no recording state, no animation, no tooltip.

### Gap
- Entirely absent. There is no voice input implementation in Dispatch's chat screen.

### Recommendation
- Jetchat's `RecordButton` is a self-contained composable with clean gesture callbacks. It can be adapted directly. The gesture modifier (`voiceRecordingGesture`) is separate and reusable.
- Decide whether voice input in MessagesScreen should record and transcribe to text (inserting into the text field) or record and send as an audio message. The gesture architecture supports either.

---

## 5. Scroll-to-Bottom Button (JumpToBottom)

### Jetchat
- `JumpToBottom` is a `ExtendedFloatingActionButton` anchored to `Alignment.BottomCenter` of the message list `Box`.
- Visibility is driven by `derivedStateOf` — it uses Compose's snapshotting to avoid recomposition on every scroll event, only recomputing when the threshold condition changes.
- Animation uses `updateTransition` to smoothly slide the button in/out vertically using an offset animation. The button is hidden by being offset off-screen below, not by toggling `AnimatedVisibility` (avoids layout shift).
- Threshold is 56dp of scroll offset from the bottom.
- The button reads "Jump to bottom" with an arrow icon.

### Dispatch (Current)
- No `JumpToBottom` equivalent exists.
- The only scroll mechanism is the `LaunchedEffect` that auto-scrolls on every new message.

### Gap
- The button is entirely absent. When a conversation is active and the user has scrolled up to read prior context, they have no way to return to the live feed without manual scrolling.

### Recommendation
- Add `JumpToBottom` inside the message list `Box` above `LazyColumn`. This is a direct, isolated addition.
- Use `derivedStateOf` to gate visibility — do not use a simple `State` variable that triggers on every scroll frame.

---

## 6. Message Formatting (Markdown, Links, Code Blocks)

### Jetchat
- `MessageFormatter` parses message text with a single lazy-compiled regex that matches URLs, inline code (`backtick`), @mentions, bold (`*word*`), italic (`_word_`), and strikethrough (`~word~`).
- Returns an `AnnotatedString` with `SpanStyle` for each matched token. Code spans get a monospace font, reduced size, and a tinted background. Links and @mentions get a color highlight.
- `ClickableText` is used to render the result, with a click handler that inspects annotations to open URLs in the browser or navigate to a user's profile.
- Color adaptation: code background and link color are inverted for "me" bubbles vs. "other" bubbles to remain readable on both primary and surfaceVariant backgrounds.

### Dispatch (Current)
- Plain `Text` composable is used for all bubble content. No inline formatting is applied.
- URLs in messages are displayed as literal text and are not clickable.
- No bold, italic, code, or @mention styling.

### Gap
- Dispatch has no message formatting layer at all. Given that the messages come from AI agents which commonly produce Markdown-formatted text (bold terms, code identifiers, URLs), this is a significant readability gap.

### Recommendation
- Adopt Jetchat's `MessageFormatter` approach. It's a pure function taking a string and a `primary: Boolean` flag, returning an `AnnotatedString`. It can be dropped into Dispatch's bubble composables with minimal changes.
- Extend the regex for code blocks (triple backtick) if agents produce multi-line code — Jetchat only handles inline code.
- Replace `Text` with `ClickableText` in `AgentBubble` and `NigelBubble` to support tappable URLs.

---

## 7. Theme and Color Scheme (Material3 Usage, Dynamic Color)

### Jetchat
- Full Material3 color scheme with both light and dark variants defined explicitly (`JetchatLightColorScheme`, `JetchatDarkColorScheme`).
- Dynamic color is enabled by default on Android 12+ (S): `dynamicDarkColorScheme` and `dynamicLightColorScheme` replace the static schemes when supported.
- Color palette is blue-centric (Blue, DarkBlue) with a yellow tertiary and red error. All colors are named by hue and shade (Blue10 through Blue90), following the Material3 tonal system.
- All in-UI color references use `MaterialTheme.colorScheme.*` tokens — no hard-coded color values in composables.
- Both light and dark themes are defined and exercised in `@Preview` annotations.

### Dispatch (Current)
- Dark-only: only `darkColorScheme` is defined. `DispatchTheme` has no light variant and no dynamic color support.
- Surface token hierarchy is well-specified (Lowest → Highest container levels), which is a strength — but the full `surfaceContainerHigh` through `surfaceContainerHighest` chain is defined and used appropriately in `MiniPlayerBar`.
- Google Blue `Primary = 0xFFA8C7FA` and associated containers are defined, matching Material3 dark-mode blue.
- Bubble composables bypass the color scheme: `NigelBubble` uses `0xFF0842A0` directly (which happens to equal `PrimaryContainer`, but isn't referenced as such), and `AgentBubble` uses `0xFF282A2C` (equals `SurfaceContainerHigh`).
- No light mode support. On systems set to light mode, the dark theme will override the system preference.

### Gap
- No light theme. Dynamic color is not wired.
- Hard-coded literals inside composables undermine the token system already established in `Color.kt`.
- The bubble background literals duplicating Color.kt constants are a maintenance hazard.

### Recommendation
- Wire existing named constants from `Color.kt` into bubble composables instead of using hex literals — `PrimaryContainer` for Nigel bubbles, `SurfaceContainerHigh` for Agent bubbles. No new colors needed.
- Optionally add a light color scheme. The current palette is well-structured enough that inverting the surface levels would yield a usable light variant.
- Consider opting into dynamic color (Android 12+) for a future enhancement, matching Jetchat's approach.

---

## 8. Typography

### Jetchat
- Uses two custom Google Fonts: Montserrat (headings, display, labels) and Karla (body, bodySmall, titleSmall).
- Fonts are loaded via `GoogleFont.Provider` at runtime with local bundled fallbacks — the app doesn't block on font download.
- Full `Typography` object is defined with 13 text styles explicitly specifying font family, weight, size, line height, and letter spacing.
- The use of two distinct font families creates a visual hierarchy: Montserrat is geometric and clean for UI chrome, Karla is humanist and readable for message body text.

### Dispatch (Current)
- `Typography` defines only three styles explicitly: `bodyLarge`, `titleLarge`, `labelSmall`. All other styles fall back to Material3 defaults.
- `FontFamily.Default` is used throughout — the system font (Roboto on most Android devices). No custom fonts.
- This is functional but undifferentiated. All text in the app uses the same typeface at varying weights.

### Gap
- Dispatch's typography file is a skeleton — 10 of 13 Material3 type roles are left as defaults.
- There is no visual distinction between UI chrome text and message body text.
- Custom fonts are entirely absent, though this may be intentional for a utility tool app.

### Recommendation
- At minimum, flesh out all 13 type roles so the full Material3 type scale is intentional rather than accidental. This prevents unintended visual changes if the Material3 library updates its defaults.
- Custom fonts are optional. If Dispatch is positioned as a professional tool, a single readable body font (like Noto Sans or IBM Plex Sans) would improve agent message legibility without requiring two families.

---

## 9. State Management Pattern

### Jetchat
- `ConversationUiState` is a plain class (not a ViewModel or data class) that holds a `MutableList<Message>` backed by Compose's `toMutableStateList()`. Changes to the list trigger recomposition automatically.
- State is instantiated at the `@Composable` call site or from a simple ViewModel — no coroutines, no flows, no Room.
- State flows unidirectionally: `ConversationContent` receives `uiState`, child composables receive only the slices they need.
- This is appropriate for the sample's scope (in-memory fake data) and demonstrates the simplest valid pattern.

### Dispatch (Current)
- `MessagesViewModel` uses `StateFlow` for all state: `bubbles`, `isSending`, `isStreaming`, `streamingText`, `streamingToolStatus`, `playingSequence`.
- Bubbles flow from a Room DAO through `flatMapLatest` → `stateIn` into the UI. Room's `InvalidationTracker` drives automatic updates on any insert.
- Streaming state (`isStreaming`, `streamingText`, `streamingToolStatus`) represents live SSE token accumulation, a dimension entirely absent from Jetchat.
- `ChatViewModel` fetches sessions from a remote API via coroutines, with separate `isRefreshing` state.
- All ViewModels use constructor injection via Hilt, following production Android architecture patterns.
- The pattern is substantially more sophisticated than Jetchat's sample — this is correct for a production app.

### Gap
- No gap in the architecture layer — Dispatch's pattern is more advanced. The main deficit is at the UI layer: the rich state available in the ViewModel (e.g., `hasEarlier`) is not fully wired to the UI (no "load earlier" trigger).

### Recommendation
- Keep Dispatch's ViewModel/Flow/Room pattern. It is the right production architecture.
- Wire `hasEarlier` and `loadEarlier()` to a scroll-to-top trigger or a "Load earlier messages" banner at the top of the message list.
- The `isLoadingEarlier` state exists in the ViewModel but is collected nowhere in the UI.

---

## 10. Navigation Structure

### Jetchat
- Uses Fragment-based navigation with the Jetpack `NavController` and an XML nav graph (`nav_home`, `nav_profile` destinations).
- The drawer is a Compose `ModalNavigationDrawer` wrapping an `AndroidViewBinding` that hosts the Fragment nav graph. This is a hybrid Compose/Fragment architecture.
- Conversation → Profile navigation is driven by tapping an author name/avatar: `navigateToProfile()` callback propagates up to the NavController call.
- The drawer contains hardcoded channel list and profile links. No dynamic content from a data source.

### Dispatch (Current)
- Pure Compose navigation using a manual state machine in `DispatchApp`. Navigation is driven by boolean flags and nullable data classes (`conversationParams`, `liveSessionParams`, `showCompose`), not a NavController.
- Back navigation is handled by resetting these flags (setting them to null/false).
- Tab navigation uses a `BottomNavBar` with a `NavigationBar` and four `DispatchTab` entries.
- Deep screen transitions (Chat → MessagesScreen → back) are managed by setting/clearing `conversationParams` rather than pushing/popping a back stack.
- No NavController, no nav graph, no deep link support.

### Gap
- Dispatch's navigation works but doesn't scale well. Adding a sixth tab or a deep link to a specific conversation would require touching `DispatchApp` and the flag system.
- Back stack is manually managed — pressing hardware back relies on composable `BackHandler` in leaf screens, not a systematic back stack.
- No deep links to specific conversations.

### Recommendation
- For current scope, the flag-based approach is defensible. The screens are shallow (no more than two levels deep) and the logic is readable.
- If Dispatch grows to 6+ destinations or needs deep links (e.g., from a notification), migrate to Jetpack Compose Navigation with a `NavHost` and typed routes. This is a significant refactor but follows the standard production pattern.
- Jetchat's hybrid Fragment/Compose navigation is not worth adopting — it's an artifact of the sample being written during the transition period and adds complexity.

---

## 11. Dispatch-Specific Components (No Jetchat Equivalent)

These components exist in Dispatch and have no parallel in Jetchat. They should be preserved and evolved.

### MiniPlayerBar
- A persistent media-player overlay that slides up from the bottom when audio is playing. Modeled after the Spotify/Google Messages mini player.
- Shows sender avatar, name, message preview, and a progress accent line.
- Has play/pause, replay, and skip-next controls. Skip-next is conditionally shown only when there are queued messages.
- Uses `slideInVertically`/`slideOutVertically` for animated appearance. The `visible = state.isActive` toggle is a clean pattern.
- Uses `surfaceContainerHigh` (a theme token) for its background — correctly using the design system. This is better than the inline bubble colors.
- The recording state (showing reply target in red) is a unique capability.

### AgentAvatar
- Generates initials and a consistent color from a name string using a hash of the name to pick from a 16-color palette.
- Handles multi-word names, hyphenated names, and single-word names.
- Correctly selects dark text for light background colors (Lime, Yellow, Amber, Orange index range).
- Used in the chat list, the message header, the mini player, and the compose To: chips. Good reuse.
- No accessibility label is passed to the `Box`. Screen readers will not announce what the avatar represents.

### StreamingBubble
- Shows live token accumulation during SSE streaming. Two states: blank (shows "Thinking..." with a spinner) and accumulating (shows token text as it arrives).
- Has an overlay for tool status: shows tool name with an animated spinner and dismisses when the tool completes.
- This is entirely novel — no chat app reference has this because they don't have agentic AI streaming tool use.

### ToolBubble
- Renders tool invocation records inline in the conversation timeline as small, italicized pills.
- Styled distinctly from chat bubbles: no alignment to left/right, centered pill shape, `DgToolBubble` dark tint.
- Allows the user to see what tools the agent used, when it used them.

### SendScreen
- A compose-email-style interface for initiating new conversations. Has "To" field with multi-select chips, thread picker with dropdown, message body, file attachment list, and a "wake agent on send" toggle.
- Thread picker: for single-department sends, loads recent threads and auto-selects one within 24 hours. Dropdown shows thread age (relative: "2h ago").
- File picker: supports multi-file selection, reads bytes in-memory, displays as removable chip cards.
- The `BackHandler` integration ensures the Back button dismisses the screen.

---

## Summary: Priority Recommendations

**High — low effort, high visual/UX impact:**
1. Wire bubble colors to `Color.kt` named constants instead of hex literals (prevents light-mode breakage, already defined).
2. Add `JumpToBottom` button (self-contained Jetchat composable, directly adoptable).
3. Guard the auto-scroll so it doesn't interrupt a user reading history.
4. Show timestamps once per sender run, not on every bubble.

**Medium — meaningful UX improvements:**
5. Add `MessageFormatter` for inline Markdown and clickable URLs in agent bubbles.
6. Add inter-run spacing: 8dp top padding when sender changes.
7. Show a "load earlier" trigger at the top of the message list (the ViewModel already supports it).
8. Wrap the input bar in a `Surface(tonalElevation = 2.dp)` and move IME padding to it.
9. Add `resetScroll` (scroll to bottom) on message send.

**Lower — larger effort or lower immediate impact:**
10. Implement `RecordButton` voice input (Jetchat's gesture composable is adoptable but requires audio recording wiring).
11. Flesh out the full Typography scale (13 roles instead of 3).
12. Add a light theme variant to `DispatchTheme`.
13. Consider Jetpack Compose Navigation when destination count grows or deep links are needed.
14. Add accessibility labels to `AgentAvatar`.
