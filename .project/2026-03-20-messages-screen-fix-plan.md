# MessagesScreen Fix Plan — 2026-03-20
## Ninth attempt. This is the definitive root-cause analysis and fix.

---

## THE ONE ROOT CAUSE

Every previous fix attempt has failed for the same reason: **`imePadding()` is applied to the
wrong container, in the wrong position in the hierarchy.**

Here is the exact problem in the current code:

```kotlin
// MessagesScreen.kt:120-126
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .statusBarsPadding()
        .imePadding()   // <-- WRONG PLACE
) {
    ConversationHeader(...)       // header
    Box(modifier = Modifier.weight(1f)) {
        LazyColumn(...)           // message list
        JumpToBottom(...)
        SnackbarHost(...)
    }
    InputBar(...)                 // input bar
}
```

When the keyboard opens, `imePadding()` shrinks the **entire Column** from the bottom.
The Column then redistributes that space. The header stays fixed. The `weight(1f)` Box
(LazyColumn) **shrinks**. The InputBar stays at the bottom of the shrunk Column.

**The result:** There is now a gap between the InputBar bottom edge and the keyboard top,
because `imePadding()` consumed the keyboard height from the Column, but nothing pushes
the Column's content flush against the keyboard. The system thinks everything is handled but
the visual result is wrong. Additionally, the `navigationBarsPadding()` in `InputBar.kt:64`
adds MORE space between the input bar and the keyboard on gesture-nav devices.

**The secondary cause:** The `LazyColumn` shrinks when the keyboard opens (viewport reduction),
but the manual scroll-on-shrink hack (`LaunchedEffect(layoutInfo.viewportEndOffset)` at
`MessagesScreen.kt:98-107`) is racy and fires after layout, causing visible jank.

---

## HOW GOOGLE MESSAGES (AND JETCHAT) SOLVE THIS

### Jetchat's pattern (the canonical Android sample, in `.project/modules/jetchat-reference`):

```kotlin
// Conversation.kt:167-218
Scaffold(
    topBar = { ... },
    // KEY: Exclude IME and nav bars from Scaffold's content insets entirely
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets
        .exclude(WindowInsets.navigationBars)
        .exclude(WindowInsets.ime),
) { paddingValues ->
    Column(
        Modifier.fillMaxSize().padding(paddingValues)
    ) {
        Messages(modifier = Modifier.weight(1f), ...)
        UserInput(
            // KEY: imePadding() and navigationBarsPadding() go HERE, on the InputBar only
            modifier = Modifier.navigationBarsPadding().imePadding(),
        )
    }
}
```

**Why this works:**
1. `imePadding()` is on the `UserInput` composable (the InputBar), NOT the parent Column.
2. When the keyboard opens, only the InputBar receives the padding signal.
3. The InputBar pushes UP, staying flush with the keyboard. There is zero gap.
4. The LazyColumn (message list) above it automatically shrinks because Column layout
   redistributes: InputBar grows taller (due to imePadding), weight(1f) gets less space.
5. No manual scroll hacks needed — the viewport shrink is handled by Compose layout,
   and the auto-scroll LaunchedEffect just needs to fire on bubbles.size change.

### Google Messages approach (from ARCHITECTURE.md + STYLE-SPEC.md):
- Uses `fitsSystemWindows="true"` on the root view with a custom `WindowInsetsDispatcher`
- The compose layer is equivalent: exclude IME from Scaffold, let the input bar own imePadding
- Status bar: transparent with edge-to-edge (matches our `enableEdgeToEdge()` in MainActivity)
- **No gap**: the compose bar and keyboard are flush because the IME inset is consumed
  at the lowest possible node in the tree — the input surface itself.

---

## THE DOUBLE-PADDING BUG

In addition to the wrong placement of `imePadding()`, there is a double-padding bug:

`MessagesScreen.kt:120`: `.imePadding()` on the outer Column
`InputBar.kt:64`: `.navigationBarsPadding()` on the InputBar Row

When the keyboard is open on a gesture-navigation device:
- `navigationBarsPadding()` adds the nav bar height (0dp when keyboard is open, correct)
- But `imePadding()` on the Column already consumed keyboard height from the outside
- The keyboard inset is being partially double-consumed, creating inconsistent gaps

The fix: remove `imePadding()` from the Column, add it to InputBar where `navigationBarsPadding()`
already lives. These two modifiers belong together on the InputBar — that is the Jetchat pattern.

---

## WHY THIS KEEPS BREAKING (THE META-PROBLEM)

Every time this is "fixed," the fix targets symptoms (scroll position, gap size) instead of the
structural cause. The viewport-shrink hack at `MessagesScreen.kt:98-107` is evidence: it was
added to compensate for a layout that doesn't handle keyboard appearance correctly. Each
subsequent attempt adds more compensating logic instead of removing the misplaced `imePadding()`.

The architectural question is: **who owns the IME inset?**
The answer is: **the widget that is pushed by the keyboard** — which is the InputBar.
The LazyColumn does not need to know about the keyboard. The Column does not need to know.
Only the InputBar needs to know, and it already has `navigationBarsPadding()`.

---

## EXACT CODE CHANGES REQUIRED

### Change 1: MessagesScreen.kt — Remove `imePadding()` from the outer Column

```kotlin
// BEFORE (MessagesScreen.kt:120-126):
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .statusBarsPadding()
        .imePadding()   // REMOVE THIS
) {

// AFTER:
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .statusBarsPadding()
        // imePadding removed — InputBar owns it now
) {
```

**File:** `MessagesScreen.kt` line 125

---

### Change 2: MessagesScreen.kt — Remove the viewport-shrink scroll hack

The hack at lines 95-107 exists only to compensate for the broken IME handling.
Once imePadding is moved to InputBar, the viewport no longer shrinks unpredictably,
and the existing auto-scroll at lines 85-93 (bubbles.size + isStreaming) is sufficient.

```kotlin
// REMOVE ENTIRELY (MessagesScreen.kt:95-107):
val layoutInfo = listState.layoutInfo
val previousHeight = remember { mutableIntStateOf(0) }
LaunchedEffect(layoutInfo.viewportEndOffset) {
    val currentHeight = layoutInfo.viewportEndOffset
    if (previousHeight.intValue > 0 && currentHeight < previousHeight.intValue) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.size - 1)
        }
    }
    previousHeight.intValue = currentHeight
}
```

**File:** `MessagesScreen.kt` lines 95-107

---

### Change 3: InputBar.kt — Add `imePadding()` to the Row modifier

The InputBar already has `navigationBarsPadding()`. Add `imePadding()` immediately after it.
These two must be adjacent — `navigationBarsPadding()` handles nav bar, `imePadding()`
handles keyboard. Order matters: navigationBarsPadding first, then imePadding.

```kotlin
// BEFORE (InputBar.kt:61-66):
Row(
    modifier = modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.Bottom
) {

// AFTER:
Row(
    modifier = modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .imePadding()   // ADD THIS — owns the keyboard inset now
        .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.Bottom
) {
```

**File:** `InputBar.kt` line 64 (add `.imePadding()` between `.navigationBarsPadding()` and `.padding(...)`)

---

### Change 4 (optional but recommended): Verify windowSoftInputMode

The Manifest (`AndroidManifest.xml:55-63`) does NOT declare `windowSoftInputMode` for MainActivity.
This means the system default applies. On modern Android with `enableEdgeToEdge()` active
(which is called in `MainActivity.kt:45`), the default is effectively `adjustResize` behavior
handled via WindowInsets — which is correct for this approach.

**No change needed here**, but note: if `windowSoftInputMode="adjustPan"` were set,
`imePadding()` would have no effect and the layout would pan instead of resize.
Keep the manifest as-is (no explicit windowSoftInputMode).

---

## PERFORMANCE: THE N+1 RENDERING ISSUE

The performance problem is separate from the keyboard issue but worth addressing.

**Root cause:** `items(chatBubbles.size) { index -> ... }` with inline grouping logic
at `MessagesScreen.kt:141-200` re-evaluates the grouping for ALL items on every
recomposition (every new bubble, every streaming update). With 50 bubbles, that is
50 item lambdas re-running. With 200 bubbles, 200.

**Why it's bad:** The `isStreaming` and `streamingText` states change on every token
during streaming. Each token change triggers recomposition of MessagesScreen, which
re-renders the entire LazyColumn item set.

**The fix:** Hoist the grouping computation out of the `items` block using `remember`:

```kotlin
// In MessagesScreen, above the LazyColumn:
val annotatedBubbles = remember(bubbles) {
    bubbles.mapIndexed { index, bubble ->
        val prev = bubbles.getOrNull(index - 1)
        val next = bubbles.getOrNull(index + 1)
        AnnotatedBubble(
            bubble = bubble,
            isFirstInRun = prev == null || prev.type != bubble.type,
            isLastInRun = next == null || next.type != bubble.type,
            currentDate = timestampToDate(bubble.timestamp),
            prevDate = prev?.let { timestampToDate(it.timestamp) },
        )
    }
}
```

Then in the LazyColumn, use `items(annotatedBubbles, key = { it.bubble.sequence })`:
- The `key` parameter enables Compose to reuse existing item compositions when
  items are appended at the end. Without a key, every item recomposes on list change.
- The `remember(bubbles)` ensures grouping is only re-computed when the bubbles list
  reference changes, not when `isStreaming` or `streamingText` changes.

The streaming bubble and typing indicator are already in separate `item {}` blocks —
those are fine. The problem is the main `items` block with the inline computation.

**Note:** This is a real improvement but lower priority than the keyboard fix. The keyboard
bug is user-visible and regression-inducing. The performance issue is a jank problem.

---

## IMPLEMENTATION ORDER

1. **Apply Change 2 first** (remove the scroll hack) — it's the most likely source of
   conflicting behavior during testing.
2. **Apply Change 1** (remove imePadding from Column).
3. **Apply Change 3** (add imePadding to InputBar).
4. Build and test: keyboard open/close should be gap-free and message list should
   scroll up smoothly as one piece.
5. **Apply the performance fix (Change 4 pattern)** as a follow-up.

---

## ACCEPTANCE CRITERIA

After the fix, verify all of the following:

| Test | Expected |
|---|---|
| Open keyboard from idle | InputBar rises to sit flush against keyboard. Zero gap. |
| Message list position when keyboard open | Last message visible above InputBar, no overlap, no gap |
| Messages + InputBar scroll as one piece | YES — Column layout handles this automatically |
| Close keyboard | InputBar descends back to nav bar position. No jank. |
| Send a message with keyboard open | List scrolls to bottom, keyboard stays open |
| Streaming response | Each token appends without re-rendering previous bubbles |
| JumpToBottom button | Appears/disappears correctly relative to InputBar |
| SnackbarHost | Appears above InputBar (already correct — it's in the Box) |

---

## FILES TO CHANGE

| File | Lines | Change |
|---|---|---|
| `MessagesScreen.kt` | 125 | Remove `.imePadding()` |
| `MessagesScreen.kt` | 95-107 | Remove viewport-shrink LaunchedEffect block |
| `InputBar.kt` | 64 | Add `.imePadding()` after `.navigationBarsPadding()` |

Three changes. Total diff: ~15 lines removed, 1 line added.
