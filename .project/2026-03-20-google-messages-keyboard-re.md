# Google Messages — Keyboard/IME Handling Reverse Engineering

**Date:** 2026-03-20
**APK:** `com.google.android.apps.messaging`
**Compiled SDK:** API 36 (Android 16 / Baklava)
**Source:** Decompiled APK at `/home/xoom000/digital-gnosis/engineering/re/google-messages/decompiled/`

---

## Summary

Google Messages uses a **legacy ViewTreeObserver-based IME detection** approach — NOT the modern `WindowInsetsAnimationCallback` API. The conversation screen has **no `windowSoftInputMode` set in the manifest** (defaults to unspecified), and IME height changes are propagated through a custom `ImeUtil` (`dntc`) listener chain. Gap prevention is achieved by the OS's default `adjustResize` behavior combined with `fitsSystemWindows="true"` on the root `ImeDetectCoordinatorLayout`.

---

## 1. windowSoftInputMode

**Finding: ConversationActivity has NO `windowSoftInputMode` set.**

From `AndroidManifest.xml`:
- `com.google.android.apps.messaging.conversation.screen.ConversationActivity` — **no `windowSoftInputMode` attribute** at all
- `com.google.android.apps.messaging.main.MainActivity` — `adjustResize|stateHidden`
- `com.google.android.apps.messaging.ui.conversation.details.ConversationDetailsActivity` — `stateAlwaysHidden|adjustResize`
- `com.google.android.apps.messaging.lighterconversation.LighterConversationActivity` — `stateHidden` only (no adjust mode!)
- `com.google.android.apps.messaging.ui.mediapicker.c2o.location.picker.LocationAttachmentPickerActivity` — `adjustNothing`

**Implication:** The ConversationActivity relies on the OS default `adjustResize` behavior. The framework resizes the window's visible area when the keyboard appears.

`zfv` (`smali_classes5/zfv.smali`) is a `SoftInputModeManager` utility that reads and programmatically sets `Window.setSoftInputMode()`. It is used by `amtx` (a lifecycle-aware coordinator) and is capable of dynamically changing the mode at runtime.

---

## 2. Root Layout Structure

**File:** `res/layout/main_activity_full_view.xml`

```xml
<com.google.android.apps.messaging.ui.ImeDetectCoordinatorLayout
    android:id="@id/conversation_and_compose_container_full_view"
    android:fitsSystemWindows="true"
    android:layout_width="fill_parent"
    android:layout_height="fill_parent">

    <androidx.coordinatorlayout.widget.CoordinatorLayout
        android:id="@id/conversation_list_root_container"
        android:fitsSystemWindows="true" ... />

    <androidx.coordinatorlayout.widget.CoordinatorLayout
        android:id="@id/conversation_root_container"
        android:fitsSystemWindows="true" ... />

    <include layout="@layout/toolbar_conversation" />
</com.google.android.apps.messaging.ui.ImeDetectCoordinatorLayout>
```

**Key:** `fitsSystemWindows="true"` on ALL layers. This means the system automatically insets the view hierarchy for the status bar, nav bar, and — with `adjustResize` — for the keyboard.

---

## 3. ImeDetectCoordinatorLayout — The Core IME Detection Mechanism

**File:** `smali_classes3/com/google/android/apps/messaging/ui/ImeDetectCoordinatorLayout.smali`
**Parent:** `didr` → `androidx.coordinatorlayout.widget.CoordinatorLayout`

### How it works

`ImeDetectCoordinatorLayout` is a `CoordinatorLayout` subclass that:

1. **`onAttachedToWindow()`**: Gets `Activity.getWindow().getDecorView().getViewTreeObserver()` and calls `addOnGlobalLayoutListener(didw)`.
2. **`onDetachedFromWindow()`**: Removes the listener via `removeOnGlobalLayoutListener(didw)`.

The listener object is `didw` (`smali_classes3/didw.smali`) — an `OnGlobalLayoutListener` implementation.

### `didw.onGlobalLayout()` — IME Detection Logic

This is the **legacy IME height detection algorithm**:

```
1. Read Window.getAttributes().softInputMode
2. If softInputMode == ADJUST_NOTHING (0x30), bail out immediately (do nothing)
3. Check if edge-to-edge mode is active (via dena.e() → aca$$...ApiModelOutline0.m(Activity))
4. If NOT edge-to-edge:
   a. Call View.getWindowVisibleDisplayFrame(Rect)  — gets the visible window rect
   b. Call WindowManager.getDefaultDisplay().getSize(Point)  — gets total screen size
   c. IME height = screen_height - visible_rect.height()
   d. If visible_rect.height() > screen_height: IME height = -1 (invalid, skip)
5. If IME height changed since last time: notify via dntc (ImeUtil)
   - Landscape: notify via dntc.f (dntb "last_ime_height_landscape")
   - Portrait: notify via dntc.e (dntb "last_ime_height")
```

**This is the classic getWindowVisibleDisplayFrame hack** — the pre-API 30 way to detect IME height. There is **no `WindowInsetsAnimationCallback`** usage found anywhere in the codebase.

---

## 4. WindowInsets Handling

### System Window Insets (nav bar / status bar)

**File:** `smali_classes5/amtx.smali` + `amtw.smali`

`amtx` is a lifecycle-aware coordinator that:
1. Finds `android.R.id.content` (view ID `0x1020002`)
2. Attaches `amtw` as an `OnApplyWindowInsetsListener`

`amtw.onApplyWindowInsets()` explicitly applies all 4 system window insets as **padding** to the content view:

```
View.setPadding(
    WindowInsets.getSystemWindowInsetLeft(),
    WindowInsets.getSystemWindowInsetTop(),
    WindowInsets.getSystemWindowInsetRight(),
    WindowInsets.getSystemWindowInsetBottom()
)
```

**This is the old `View.OnApplyWindowInsetsListener` API** (not `ViewCompat.setOnApplyWindowInsetsListener`). It applies system insets (including bottom nav bar) directly as padding. With `adjustResize`, the bottom inset changes when the keyboard opens.

### IME Insets (Keyboard)

**No `WindowInsetsCompat` or `WindowInsetsAnimation` usage found.** The keyboard height is detected exclusively through the `getWindowVisibleDisplayFrame` mechanism in `didw.onGlobalLayout()`.

### Edge-to-Edge Check

`dena.e(Activity, boolean)` checks if the activity is edge-to-edge by calling `aca$$ExternalSyntheticApiModelOutline0.m(Activity)`. This wraps a platform API (likely `WindowCompat.getInsetsController()` or `View.getWindowInsetsController()` API). If edge-to-edge is active, the IME detection in `didw` is skipped (the system handles it differently).

---

## 5. Conversation Screen Layout Structure

**File:** `res/layout/conversation_view_layout.xml` (the main conversation view)

```xml
<FrameLayout
    android:animateLayoutChanges="true"
    android:layout_width="fill_parent"
    android:layout_height="fill_parent">

    <!-- LighterWebView for fallback -->
    <LighterWebView android:id="@id/lighter_web_view_body" android:visibility="gone" />

    <LinearLayout
        android:orientation="vertical"
        android:id="@id/conversation_body"
        android:layout_width="fill_parent"
        android:layout_height="fill_parent">

        <!-- Message list in CoordinatorLayout -->
        <CoordinatorLayout android:id="@id/coordinator_body"
            android:layout_width="fill_parent"
            android:layout_height="0dip"
            android:layout_weight="1">

            <AppBarLayout android:id="@id/app_bar" ... />

            <MessageListView android:id="@id/messages_list"
                android:paddingBottom="@dimen/lt_message_list_padding_bottom"
                android:clipToPadding="false"
                app:layout_behavior="ConversationHeaderScrollingViewBehavior"
                app:pagingMode="more_on_top" />
        </CoordinatorLayout>

        <!-- Status bar below list -->
        <TextStatusBarHolderView android:id="@id/bottom_status_bar_holder" />

        <!-- Input bar pinned to bottom -->
        <LinearLayout android:orientation="horizontal">
            <FrameLayout android:id="@id/composer_entrypoint_view" android:visibility="gone" />
            <ComposeBoxView android:id="@id/compose_view"
                android:layout_width="fill_parent"
                android:layout_height="wrap_content" />
        </LinearLayout>
    </LinearLayout>

    <OverlayView android:id="@id/conv_overlay_view" />
</FrameLayout>
```

**Key layout facts:**
- The outer `FrameLayout` has `android:animateLayoutChanges="true"` — this enables automatic layout transition animations when child bounds change.
- The `LinearLayout` uses `layout_weight="1"` on the message list area and `wrap_content` on the input bar. This means when the window shrinks from `adjustResize`, the **message list shrinks** and the **input bar stays at the bottom naturally** — no custom animation needed.
- `MessageListView` uses `app:pagingMode="more_on_top"` — this is a **reverse-layout** list (newest messages at bottom). `clipToPadding="false"` allows content to draw behind the padding.

---

## 6. Scroll Behavior on Keyboard Open

### Mechanism: No explicit scroll-to-bottom on keyboard open

With `adjustResize` + `fitsSystemWindows="true"` + the `LinearLayout weight` approach:

1. Keyboard opens → system shrinks the window's visible area
2. `getWindowVisibleDisplayFrame` detects the new height
3. `ImeDetectCoordinatorLayout` fires `onGlobalLayout`
4. `didw` computes the new IME height and notifies `dntc` (ImeUtil) listeners
5. The `LinearLayout` automatically reflows: message list (`weight=1`) shrinks, input bar stays at bottom
6. `animateLayoutChanges="true"` on the root `FrameLayout` provides automatic transition

**There is no explicit `scrollToPosition()`, `smoothScrollToPosition()`, or `RecyclerView.scrollBy()` call detected in the IME path.** The layout system handles the visual repositioning automatically through the window resize + `adjustResize`.

The `MessageListView` with `pagingMode="more_on_top"` (reverse layout) ensures the **last message is at the visual bottom** of the list at all times — so when the list shrinks, the last message is still visible at the new bottom.

---

## 7. Input Bar Animation

**Finding: The input bar does NOT animate — it jumps.**

- `animateLayoutChanges="true"` on the root FrameLayout provides a basic `LayoutTransition` (Android's default layout animation system).
- The actual keyboard show/hide transition is driven by `adjustResize` window resizing, which is a **synchronous, frame-based resize** — NOT a smooth inset animation.
- There is **no `WindowInsetsAnimationCallback`** (API 30+) found, which would allow animating the input bar in sync with the keyboard slide.

**This is the key finding:** Google Messages uses the OLD approach that snaps/jumps rather than smoothly following the keyboard.

---

## 8. Gap Prevention

The gap between keyboard, input bar, and message list is prevented by:

1. **`adjustResize`** (default, unset = OS default) on `ConversationActivity`: The OS shrinks the window's visible bounds.
2. **`fitsSystemWindows="true"`** on `ImeDetectCoordinatorLayout` (root): System window insets (nav bar) are consumed at the root level.
3. **`LinearLayout` with `layout_weight="1"` on message list**: The message list fills available space; when the window shrinks, the message list shrinks, not the input bar.
4. **`amtw.onApplyWindowInsetsListener`** on `android.R.id.content`: Applies nav bar inset as bottom padding, preventing overlap with the navigation bar.

**There is no custom gap-prevention code** — it's entirely structural (layout weights + system insets).

---

## 9. Edge-to-Edge Handling

The app checks for edge-to-edge mode in `dena.e()`. If edge-to-edge is active:
- The `didw.onGlobalLayout` IME detection is **bypassed**
- The `dntc.b(false)` is called, marking IME as hidden/collapsed

This suggests that when Google Messages detects edge-to-edge mode, it **falls back to a different IME handling strategy** — likely relying on system-provided insets rather than the `getWindowVisibleDisplayFrame` hack.

The `amtw` inset listener still applies system insets as padding in edge-to-edge mode, which would include IME insets if the OS provides them.

---

## 10. IME Show/Hide API

**File:** `smali_classes3/dntc.smali` — `ImeUtil`

Two code paths for showing the keyboard:

**Modern path (API 30+, if feature flag `dena.d` is set):**
```
View.requestFocus()
View.getWindowInsetsController().show(WindowInsets.Type.ime())
dntc.b(true)  // mark IME as shown
```

**Legacy path (fallback):**
```
View.requestFocus()
InputMethodManager.showSoftInput(view, 0, ResultReceiver)
dntc.b(true)  // mark IME as shown
```

For **hiding**:
```
InputMethodManager.hideSoftInputFromWindow(windowToken, 0, ResultReceiver)
dntc.b(false)  // mark IME as hidden
```

The `ResultReceiver` (`dnta`) is used to receive the show/hide result callbacks, which then update the `dntc.b` (isImeOpen) state.

---

## 11. IME Height Persistence

The last known IME height is stored in:
- `dntb.a` (int field, initialized to -1)
- Key: `"last_ime_height"` (portrait) / `"last_ime_height_landscape"` (landscape)
- Storage backend: `dfeb` (likely SharedPreferences or a key-value store)

This allows Google Messages to pre-size the composer area before the keyboard appears, preventing layout jumps on first open.

---

## 12. Obfuscated Class Map (Keyboard/IME)

| Obfuscated | Inferred Real Name | Role |
|---|---|---|
| `ImeDetectCoordinatorLayout` | ImeDetectCoordinatorLayout | Root CoordinatorLayout with IME detection |
| `didr` | BaseImeCoordinatorLayout | CoordinatorLayout subclass (parent of above) |
| `didw` | ImeGlobalLayoutListener | OnGlobalLayoutListener: computes IME height |
| `didx` | ImeDetectorFactory | Creates ImeGlobalLayoutListener instances |
| `didv` | ImeHeightSource? | Internal helper created with threshold size |
| `dntc` | ImeUtil | IME state manager: show/hide, height tracking, listener list |
| `dntb` | ImeHeightStore | Stores last IME height per orientation |
| `dnta` | ImeResultReceiver | ResultReceiver for show/hide callbacks |
| `dntq` | OrientationDetector? | Used by dntc to select portrait vs landscape store |
| `amtx` | ConversationInsetsCoordinator | Lifecycle-aware coordinator: attaches inset listeners |
| `amtw` | ContentInsetsListener | OnApplyWindowInsetsListener: applies nav insets as padding |
| `zfv` | SoftInputModeManager | Reads/writes Window.softInputMode |
| `dena` | FeatureFlags | Global feature flag registry (has `d` flag for WindowInsetsController path) |

---

## 13. Key Takeaways for Dispatch

### What Google Messages does RIGHT
1. **`adjustResize` default** — lets the OS handle the heavy lifting of shrinking the window.
2. **`fitsSystemWindows="true"` at the root** — nav bar and status bar insets handled automatically.
3. **`LinearLayout` with `weight`** — the message list shrinks, the input bar stays pinned to the bottom, no gaps.
4. **`animateLayoutChanges="true"`** — free basic layout animation from the OS.
5. **IME height pre-caching** — remembers last IME height to avoid layout jumps.

### What Google Messages does WRONG (or that we can do better)
1. **No `WindowInsetsAnimationCallback`** — the keyboard does not animate smoothly with the input bar. The input bar jumps. We can do better.
2. **Legacy `getWindowVisibleDisplayFrame` hack** — works but is fragile with edge-to-edge. We should use `ViewCompat.setWindowInsetsAnimationCallback` + IME insets from `WindowInsetsCompat`.
3. **`View.OnApplyWindowInsetsListener`** (non-compat version) — the old API. We should use `ViewCompat.setOnApplyWindowInsetsListener`.

### The specific bug: keyboard/input bar gap

The gap in **our** app likely occurs because:
1. `adjustResize` is NOT set (or `adjustNothing` is set), so the window doesn't shrink.
2. OR we're not consuming bottom insets properly, so the input bar overlaps the nav bar.
3. OR we're using Compose `imePadding()` without the activity being set up for it (requires `WindowCompat.setDecorFitsSystemWindows(window, false)` + `adjustResize`).

**The fix:** Mirror Google Messages' approach exactly:
- Set `windowSoftInputMode` to nothing (default = `adjustResize`) or explicitly set `adjustResize|stateHidden` on the ConversationActivity.
- Use `fitsSystemWindows="true"` at the root.
- Use a `LinearLayout` with `weight=1` on the message list, `wrap_content` on the input bar.
- OR in Compose: use `Modifier.imePadding()` on the scaffold/root + ensure `WindowCompat.setDecorFitsSystemWindows(window, false)` is called.

---

## Reference Files

| File | Purpose |
|---|---|
| `decompiled/AndroidManifest.xml` | Activity declarations, softInputMode attributes |
| `decompiled/smali_classes3/com/google/android/apps/messaging/ui/ImeDetectCoordinatorLayout.smali` | Root IME detector |
| `decompiled/smali_classes3/didw.smali` | OnGlobalLayoutListener (getWindowVisibleDisplayFrame IME detection) |
| `decompiled/smali_classes3/dntc.smali` | ImeUtil: show/hide keyboard, height tracking |
| `decompiled/smali_classes3/dntb.smali` | IME height persistence per orientation |
| `decompiled/smali_classes5/amtx.smali` | Lifecycle coordinator: attaches system inset listeners |
| `decompiled/smali_classes5/amtw.smali` | OnApplyWindowInsetsListener: applies nav bar insets as padding |
| `decompiled/smali_classes5/zfv.smali` | SoftInputMode reader/writer |
| `decompiled/res/layout/main_activity_full_view.xml` | Root layout with ImeDetectCoordinatorLayout |
| `decompiled/res/layout/conversation_view_layout.xml` | Conversation screen: message list + input bar structure |
| `decompiled/res/layout/lighter_conversation_fragment.xml` | LighterConversation variant layout |
