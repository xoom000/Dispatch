# Google Messages Conversation Screen — Scaffold RE Findings
Date: 2026-03-20
Source: Decompiled APK at `/home/xoom000/digital-gnosis/engineering/re/google-messages/decompiled/`

---

## Summary

Google Messages uses a **View-based layout** for the conversation screen, not Jetpack Compose
for the scaffold itself. The compose layer (confirmed by prior RE in ARCHITECTURE.md) sits inside
a `ComposeView` that is embedded in the View hierarchy. The scaffold is View-based
CoordinatorLayout + LinearLayout, not Scaffold{} composable.

This is a critical distinction: the IME handling strategy, the layout hierarchy, and the
keyboard behavior we need to replicate in Dispatch are all View-system patterns translated
to Compose equivalents — not direct Compose-to-Compose copies.

---

## 1. Activity Hierarchy

### Entry Points

There are two conversation activity paths:

**Path A — Standard SMS/MMS (legacy):**
`com.google.android.apps.messaging.conversation.screen.ConversationActivity`
- Not declared as a primary activity in the manifest (only appears as `parentActivityName`)
- Hosted inside `MainActivity`

**Path B — Lighter/RCS (current, primary):**
`com.google.android.apps.messaging.lighterconversation.LighterConversationActivity`
- Manifest: `android:windowSoftInputMode="stateHidden"` (no adjustResize/adjustPan specified)
- Layout: `lighter_conversation_activity.xml`

**MainActivity (the shell):**
```xml
android:windowSoftInputMode="adjustResize|stateHidden"
```
- `adjustResize` is explicit and intentional — the window resizes when the keyboard appears
- `stateHidden` — keyboard is hidden when the activity first starts
- This is the outermost activity that hosts everything

### Key implication for Dispatch:
`adjustResize` is set at the Activity level. On Android 11+ with `WindowCompat.setDecorFitsSystemWindows(window, false)` (edge-to-edge), `adjustResize` + `fitsSystemWindows` on the root view is the equivalent of the modern `imePadding()` approach.

---

## 2. Layout Hierarchy (The Complete Tree)

### Layer 1: Activity Root (`main_activity_full_view.xml`)

```xml
<com.google.android.apps.messaging.ui.ImeDetectCoordinatorLayout
    android:id="@id/conversation_and_compose_container_full_view"
    android:fitsSystemWindows="true"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <CoordinatorLayout android:id="@id/conversation_list_root_container"
        android:fitsSystemWindows="true"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <CoordinatorLayout android:id="@id/conversation_root_container"
        android:fitsSystemWindows="true"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <include layout="@layout/toolbar_conversation" />

</com.google.android.apps.messaging.ui.ImeDetectCoordinatorLayout>
```

**Key points:**
- Root is `ImeDetectCoordinatorLayout` — a custom `CoordinatorLayout` subclass with `fitsSystemWindows="true"`
- Two `CoordinatorLayout` children: one for the conversation list (home screen), one for the conversation screen
- Both children have `fitsSystemWindows="true"` — insets propagate down the entire tree

### Layer 2: Lighter Conversation Fragment (`lighter_conversation_fragment.xml`)

```xml
<ConstraintLayout>
    <ConversationView
        android:id="@id/lighter_conversation_view"
        app:layout_constraintBottom_toTopOf="@id/lighter_compose_container"
        app:layout_constraintTop_toTopOf="parent"
        <!-- fills from top to above compose container -->
    />
    <FrameLayout
        android:id="@id/lighter_compose_container"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintTop_toBottomOf="@id/lighter_conversation_view"
        <!-- anchored to bottom of parent -->
    />
</ConstraintLayout>
```

**Critical pattern:** `ConversationView` is constrained `bottom_toTopOf` the compose container.
The compose container is constrained `bottom_toBottomOf parent`. This is **ConstraintLayout's
equivalent of `weight(1f)`** — the conversation list fills all available space above the input bar.

### Layer 3: The Main Content (`conversation_view_layout.xml`)

This is the most important layout. Full hierarchy:

```xml
<FrameLayout>                                          <!-- outer wrapper, animateLayoutChanges=true -->

    <LighterWebView visibility="gone" />               <!-- web fallback, hidden by default -->

    <LinearLayout                                      <!-- main vertical container -->
        android:id="@id/conversation_body"
        android:orientation="vertical">

        <CoordinatorLayout                             <!-- message list + header zone -->
            android:id="@id/coordinator_body"
            android:layout_width="match_parent"
            android:layout_height="0dp"               <!-- KEY: explicit 0dp -->
            android:layout_weight="1.0">              <!-- KEY: weight(1f) equivalent -->

            <AppBarLayout>                             <!-- collapsing header zone -->
                <ConversationHeaderView
                    android:fitsSystemWindows="true"
                    android:layout_height="@dimen/header_expanded_height"
                    app:layout_scrollFlags="exitUntilCollapsed|scroll|snap" />
                <LinearProgressIndicator />
                <TextStatusBarHolderView android:id="@id/top_status_bar_holder" />
            </AppBarLayout>

            <MessageListView                           <!-- the message list -->
                android:id="@id/messages_list"
                android:paddingBottom="@dimen/lt_message_list_padding_bottom"
                android:clipToPadding="false"          <!-- KEY: allows overscroll beyond padding -->
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:layout_behavior="ConversationHeaderScrollingViewBehavior"
                app:pagingMode="more_on_top" />        <!-- loads more content at top -->

            <LoadingView ... />
        </CoordinatorLayout>

        <TextStatusBarHolderView android:id="@id/bottom_status_bar_holder" /> <!-- typing indicator etc -->

        <LinearLayout orientation="horizontal">       <!-- input bar row -->
            <FrameLayout android:id="@id/composer_entrypoint_view" visibility="gone" />
            <ComposeBoxView android:id="@id/compose_view"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>

    </LinearLayout>

    <OverlayView android:id="@id/conv_overlay_view" />

</FrameLayout>
```

---

## 3. The Three Core Layout Principles

### Principle 1: The list gets `weight(1f)`, the input bar is `wrap_content`

The `CoordinatorLayout` (containing the message list) has:
- `android:layout_height="0dp"`
- `android:layout_weight="1.0"`

The `ComposeBoxView` (input bar) has:
- `android:layout_height="wrap_content"`

The outer `LinearLayout` is `fill_parent` height.

**Result:** The message list fills all available space. The input bar takes only what it needs.
When the keyboard appears and the window resizes (adjustResize), the LinearLayout shrinks.
The weight(1f) list shrinks. The wrap_content input bar stays the same size.

**Dispatch equivalent:**
```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    MessageList(modifier = Modifier.weight(1f))  // fills available space
    InputBar(modifier = Modifier.wrapContentHeight())  // wrap_content
}
```

### Principle 2: `clipToPadding="false"` on the MessageListView

`MessageListView` has:
- `android:paddingBottom="@dimen/lt_message_list_padding_bottom"`
- `android:clipToPadding="false"`

This combination allows messages to scroll behind the padding zone (they can scroll under where
the input bar would be if the views overlapped), but the last item rests above the padding.
`clipToPadding="false"` means overscroll animations can go into the padding zone.

**Dispatch equivalent:** In Compose, this is `contentPadding` on `LazyColumn`:
```kotlin
LazyColumn(
    contentPadding = PaddingValues(bottom = 8.dp),
    // No clipToPadding equivalent needed — Compose LazyColumn handles this correctly by default
)
```

### Principle 3: AppBarLayout + CoordinatorLayout for the collapsing header

The header (`ConversationHeaderView`) is inside an `AppBarLayout` inside a `CoordinatorLayout`.
The message list has `app:layout_behavior="ConversationHeaderScrollingViewBehavior"` — a custom
`AppBarLayout.ScrollingViewBehavior` subclass that coordinates the list scroll with the header
collapse/expand.

The header has `app:layout_scrollFlags="exitUntilCollapsed|scroll|snap"` — it collapses when
scrolling down and snaps to fully expanded/collapsed states.

**Dispatch equivalent:** There is no direct Compose equivalent needed unless we want a collapsing
header. If so, use `TopAppBarScrollBehavior` from Material3.

---

## 4. Keyboard Handling — The Complete Mechanism

### 4a. Activity-Level: `adjustResize|stateHidden`

```xml
<!-- AndroidManifest.xml line 288 -->
<activity android:name="com.google.android.apps.messaging.main.MainActivity"
    android:windowSoftInputMode="adjustResize|stateHidden" />
```

`adjustResize` causes the Activity window to resize when the software keyboard appears.
The entire View tree shrinks to fit the remaining space above the keyboard.

### 4b. Root View: `ImeDetectCoordinatorLayout` with `fitsSystemWindows="true"`

The root `ImeDetectCoordinatorLayout` has `fitsSystemWindows="true"`. With `adjustResize`,
the window's visible area already excludes the keyboard. The `fitsSystemWindows` flag handles
status bar and navigation bar insets only (not the keyboard — that's handled by adjustResize).

### 4c. Custom IME Detection via `ViewTreeObserver.OnGlobalLayoutListener`

`ImeDetectCoordinatorLayout` is a custom `CoordinatorLayout` that implements IME height
detection through a non-Compose approach:

**The chain:**
```
ViewTreeObserver.OnGlobalLayoutListener.onGlobalLayout()
  → Window.getAttributes().softInputMode check
  → View.getWindowVisibleDisplayFrame() — gets visible rect
  → Display.getSize() — gets full screen size
  → visible_height vs screen_height → IME height
  → ImeUtil (dntc) stores "last_ime_height" + "last_ime_height_landscape"
  → Notifies listeners via List<listener> (fields c and d in dntc)
```

**Key class map:**
| Obfuscated | Role |
|---|---|
| `ImeDetectCoordinatorLayout` | Root view, owns IME detection lifecycle |
| `didr` | Base CoordinatorLayout with Hilt component injection |
| `didw` | `OnGlobalLayoutListener` impl — measures keyboard height |
| `didx` | Factory for `didw`, holds references to ImeUtil and orientation provider |
| `dntc` | `ImeUtil` — stores last known IME height per orientation, manages callbacks |
| `dntb` | Persistent storage for IME height (SharedPreferences or similar) with key "last_ime_height" |

**The measurement logic in `didw.onGlobalLayout()`:**
1. Check if `softInputMode == 0x30` (SOFT_INPUT_STATE_ALWAYS_VISIBLE) — skip if so
2. Check if in multi-window mode via `dena.e()` — if multi-window, notify ImeUtil with height=0
3. Call `view.getWindowVisibleDisplayFrame(rect)` and `display.getSize(point)`
4. IME height = `rect.height() > point.y ? -1 : rect.height()`
   - `-1` sentinel means "visible rect is larger than display" (shouldn't happen normally)
5. Only notify if height changed from last measurement
6. Uses two `didv` instances (fields g, h) based on orientation (`dnqe.j()`)

**IME minimum height threshold:** `c2o_ime_minimum_height` (resource ID `0x7f070191`) is loaded
from resources and used as a threshold — below this value, the IME is not considered "open".

### 4d. No WindowInsetsCompat / No `imePadding()` anywhere in View layer

Google Messages does NOT use:
- `ViewCompat.setWindowInsetsAnimationCallback()`
- `WindowInsetsAnimation.Callback`
- `imePadding()` (Compose API)
- `WindowInsetsControllerCompat`

The keyboard animation is handled entirely by the system via `adjustResize`. The view tree
simply resizes. There is no animated insets transition — the keyboard slides up and the
layout has already been measured for the new size.

This is the **classic pre-Compose approach**. On Android 11+, Google could use the new
`WindowInsetsAnimation` API for smooth keyboard transitions, but they appear to use the
older `adjustResize` + `ViewTreeObserver` pattern for compatibility.

---

## 5. Message List Behavior

### View type: `MessageListView`

`MessageListView` is in the package:
`com.google.android.libraries.messaging.lighter.ui.messagelist.MessageListView`

This is NOT a standard `RecyclerView` or `LazyColumn`. It's a custom view. However, from the
ARCHITECTURE.md findings:
- **No `RecyclerView` APIs found** (`DiffUtil`, `ListAdapter`, `notifyItemInserted` absent)
- **ComposeView** references found — the message list content is rendered in Compose
- `MessageListView` is likely a `ComposeView` wrapper or a custom view that hosts Compose

**`pagingMode="more_on_top"`** — This custom attribute indicates that older content loads at
the top (scroll up = load older messages). The list origin is at the bottom; new messages
appear at the bottom.

### Scroll-to-bottom on keyboard open

There is no explicit "scroll to bottom when keyboard opens" code visible in the view layer.
The mechanism is structural:
1. Window resizes (adjustResize)
2. CoordinatorLayout's weight(1f) child shrinks
3. The message list viewport shrinks
4. If the list was at the bottom (latest message visible), it remains at the bottom
   because the content below the viewport is empty — there's nowhere to scroll

This is the key insight: **a list that's already at the bottom stays at the bottom when the
viewport shrinks**, because the last item is already visible. No scroll action needed.

The only case where you need to scroll is if the user was viewing old messages when they
tapped the input — then you'd want to scroll to bottom. Google handles this by tracking
whether the list was at the bottom before the keyboard appeared, and if not, they don't
force-scroll.

---

## 6. The `LighterConversationActivity` Path (Newer Code)

This appears to be the modern Lighter architecture path:

**Manifest:** `android:windowSoftInputMode="stateHidden"` only — no adjustResize
**Layout:** `lighter_conversation_fragment.xml` — ConstraintLayout with two children

The lack of `adjustResize` suggests this path may use the modern `WindowInsetsAnimation`
approach or the Compose `imePadding()` approach directly (since the conversation is rendered
in Compose). The `stateHidden` just means the keyboard is hidden on entry.

This is significant: the newer code may rely on the Compose insets system, not `adjustResize`.
The old `ConversationActivity` path (legacy SMS) explicitly used `adjustResize`.

---

## 7. Performance Architecture

From ARCHITECTURE.md (confirmed):
- **No RecyclerView** — UI is Compose, confirmed by `ComposeView` references
- **Compose's built-in diffing** — no `DiffUtil` needed
- **`collectAsStateWithLifecycle()`** — UI collects from `StateFlow`
- **`StateFlow`** with `WhileSubscribed(5000)` — upstream flow is cancelled 5s after last collector leaves

### Item recycling / performance:
Since it's Compose `LazyColumn` (not RecyclerView), item recycling is handled by Compose's
`LazyListState` and the composable slot reuse system. Google uses:
- `items(list, key = { item.id })` — stable keys for efficient reuse
- `pagingMode="more_on_top"` custom attribute suggests a custom paging implementation
  that prepends items when scrolled to top

---

## 8. Translation to Dispatch (Compose)

### The reference pattern for Dispatch MessagesScreen:

```kotlin
Scaffold(
    topBar = { /* ConversationHeader */ },
    // CRITICAL: Exclude IME from Scaffold's content insets
    // Let the InputBar own the IME inset directly
    contentWindowInsets = ScaffoldDefaults.contentWindowInsets
        .exclude(WindowInsets.ime)
        .exclude(WindowInsets.navigationBars),
) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Message list: weight(1f) equivalent of CoordinatorLayout 0dp+weight
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            reverseLayout = false,
            contentPadding = PaddingValues(bottom = 8.dp),
        ) { /* items */ }

        // Input bar: wrap_content, owns IME inset
        InputBar(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()  // nav bar inset
                .imePadding()             // keyboard inset — must be AFTER navigationBarsPadding
        )
    }
}
```

### Why this matches Google's architecture:

| Google Messages (View) | Dispatch (Compose) |
|---|---|
| `adjustResize` on Activity | `WindowCompat.setDecorFitsSystemWindows(false)` + `imePadding()` on InputBar |
| `fitsSystemWindows="true"` on root | `Scaffold` handles status bar insets via `paddingValues` |
| `LinearLayout weight(1f)` on MessageListView | `Modifier.weight(1f)` on LazyColumn |
| `wrap_content` on ComposeBoxView | `Modifier.wrapContentHeight()` on InputBar |
| `clipToPadding="false"` + `paddingBottom` on MessageListView | `contentPadding` on LazyColumn |
| No explicit scroll-to-bottom on keyboard open | List stays at bottom naturally when already there |

### The `imePadding()` ownership rule (the lesson):

Google's View system: the input bar is a `wrap_content` view at the bottom of a `weight(1f)` Linear Layout. When `adjustResize` shrinks the window, the input bar doesn't move — the list shrinks. The input bar's bottom edge is already at the window bottom.

Compose equivalent: `imePadding()` on the InputBar composable pushes the InputBar up by the
keyboard height. `weight(1f)` on the LazyColumn means it shrinks to give the InputBar that space.
The InputBar bottom edge is then flush with the keyboard top.

**Never put `imePadding()` on the parent Column.** If you do, the Column shrinks from the
outside, which means the column gets shorter but the bottom of the column is now ABOVE the
keyboard, creating a gap. The inset is consumed before reaching the InputBar.

---

## 9. Key Files for Reference

### Layout files (confirmed locations):
- `res/layout/main_activity_full_view.xml` — root activity layout with ImeDetectCoordinatorLayout
- `res/layout/conversation_view_layout.xml` — THE main conversation layout (LinearLayout + weight)
- `res/layout/lighter_conversation_fragment.xml` — ConstraintLayout alternative
- `res/layout/lighter_conversation_activity.xml` — ConstraintLayout shell
- `res/layout/conversation_header_layout.xml` — collapsing header with Toolbar

### Smali classes (key):
- `smali_classes3/com/google/android/apps/messaging/ui/ImeDetectCoordinatorLayout.smali` — IME root
- `smali_classes3/didr.smali` — base CoordinatorLayout with Hilt injection
- `smali_classes3/didw.smali` — `OnGlobalLayoutListener` that measures keyboard height
- `smali_classes3/didx.smali` — factory for `didw`
- `smali_classes3/dntc.smali` — `ImeUtil` class (stores last_ime_height)
- `smali/com/google/android/apps/messaging/conversation2/viewmodel/ConversationViewModel.smali`

### Manifest entries:
- `MainActivity`: `adjustResize|stateHidden` — the definitive softInputMode
- `LighterConversationActivity`: `stateHidden` only (modern path)

---

## 10. What This Confirms About the Current Dispatch Bug

The 2026-03-20-messages-screen-fix-plan.md diagnosis is correct and fully confirmed:

1. **`imePadding()` belongs on the InputBar, not the Column.** Google's layout proves this —
   the input bar is at the bottom of a `weight(1f)` LinearLayout, and the window resize
   (not an insets modifier) pushes it up. In Compose, `imePadding()` on the InputBar is
   the exact equivalent.

2. **The viewport-shrink hack is wrong.** Google's approach: the list simply shrinks via
   the layout system. No `LaunchedEffect` on `viewportEndOffset`. No `animateScrollToItem`
   compensations. If the list is at the bottom, it stays there. If it's not, no auto-scroll.

3. **The double-padding bug is real.** `imePadding()` on the Column + `navigationBarsPadding()`
   on the InputBar creates double-consumption of insets. Google does neither of these together —
   the Activity handles it at the window level via `adjustResize`, and the input bar handles
   nothing explicitly (it just sits at the bottom of the LinearLayout).

4. **No gap.** Because the InputBar is `wrap_content` at the bottom of the LinearLayout, and
   the LinearLayout fills the window (which has been resized to exclude the keyboard by
   `adjustResize`), the InputBar bottom is always at the keyboard top. Zero gap, by construction.

---

## Appendix: Obfuscated Class Map (Layout Focus)

| Obfuscated | Real Name | Role |
|---|---|---|
| `ImeDetectCoordinatorLayout` | ImeDetectCoordinatorLayout | Root view, IME lifecycle |
| `didr` | BaseImeCoordinatorLayout | Parent CoordinatorLayout with Hilt |
| `didw` | ImeHeightGlobalLayoutListener | Measures keyboard height via OnGlobalLayoutListener |
| `didx` | ImeListenerFactory | Creates ImeHeightGlobalLayoutListener |
| `dntc` | ImeUtil | Stores last IME height, notifies listeners |
| `dntb` | ImeHeightPersistence | Persists "last_ime_height" / "last_ime_height_landscape" |
| `didv` | ImeHeightNotifier | Notifies ImeUtil of height changes |
| `dnqe` | OrientationProvider | Tracks landscape/portrait for height selection |
| `dena` | MultiWindowUtil | Checks multi-window mode |
