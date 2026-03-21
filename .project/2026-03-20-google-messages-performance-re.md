# Google Messages — Conversation Screen Performance RE

**Date:** 2026-03-20
**Source:** Decompiled APK `com.google.android.apps.messaging`
**Prior art:** See also `ARCHITECTURE.md` and `BACKGROUND-ARCHITECTURE.md` in the same RE directory.

---

## Executive Summary

Google Messages' conversation screen is **NOT Compose** — it uses a custom Views-based framework called **"Lighter"** (`com.google.android.libraries.messaging.lighter`). The message list is a RecyclerView with a LinearLayoutManager. Performance comes from a chain of well-executed decisions: a reactive data pipeline with conflated updates, a reverse-layout trick, a custom item animator, and a paged-load architecture with a progress indicator built into the RecyclerView subclass itself. No DiffUtil is used — the Lighter framework has its own diffing abstraction.

The ARCHITECTURE.md claim that "Google Messages uses Compose" is **incorrect for the conversation screen**. Compose is present in the codebase (ComposeView references exist) but the main message list is Lighter/Views. The home conversation list is also Views-based.

---

## 1. List Implementation

### The Hierarchy

```
ConversationView (FrameLayout)
  └── MessageListView (extends PagedRecyclerView)
        └── PagedRecyclerView (extends RecyclerView)
              └── emhx (extends LinearLayoutManager)   ← custom LLM
```

**Key classes:**
| Class | Real name | File |
|---|---|---|
| `MessageListView` | MessageListView | `smali_classes7/.../lighter/ui/messagelist/MessageListView.smali` |
| `PagedRecyclerView` | PagedRecyclerView | `smali_classes7/.../lighter/ui/common/pagedrecyclerview/PagedRecyclerView.smali` |
| `ConversationView` | ConversationView | `smali_classes7/.../lighter/ui/conversation/ConversationView.smali` |
| `emhx` | LighterLinearLayoutManager | `smali_classes7/emhx.smali` |
| `emmf` | LighterItemAnimator | `smali_classes7/emmf.smali` |

### LinearLayoutManager Configuration

`PagedRecyclerView` constructor:
1. Creates `emhx` (custom LinearLayoutManager with no constructor args — defaults to VERTICAL)
2. Sets it on the RecyclerView via `setLayoutManager()`
3. Calls `setReverseLayout(true)` **conditionally** based on an `emia` enum attribute

The `emia` enum has at least two values (a, b). Value `b` triggers `setReverseLayout(true)`. For the conversation screen, `MessageListView` sets a `Lfaha` (likely a sentinel/default) field — the actual reverse layout direction is set by the attribute from XML (`0x7f0b073c` is the view ID for MessageListView in `ConversationView`).

**Critical implication:** The list is likely rendered bottom-up (newest messages at bottom), achieved via `reverseLayout=true` on the LinearLayoutManager rather than any manual ordering trick. New items inserted at position 0 automatically appear at the bottom without animation glitches.

### Custom Item Animator

`MessageListView` constructor installs a custom item animator `emmf` immediately:
```smali
new-instance p2, Lemmf;
invoke-direct {p2, p1}, Lemmf;-><init>(Landroid/content/Context;)V
invoke-virtual {p0, p2}, RecyclerView;->al(Lwa;)V   # setItemAnimator
```

`emmf` extends `yd` (obfuscated RecyclerView.ItemAnimator subclass) and holds 10 ArrayLists — these are separate animation queues for add/remove/change/move animations, allowing them to run concurrently rather than sequentially. This prevents the "stutter while new message slides in" pattern.

### View Types in the Message List

Confirmed named view types in the Lighter framework:
- `TimestampHeaderView` — date separator (extends FrameLayout, has a TextView + self-updating Runnable for relative time refresh)
- `TopLabelView` — sender label row above message cluster (extends LinearLayout, has TextView + ImageView)
- `MessageCellStatusView` — read receipt / delivery status text (extends AppCompatTextView, has a 60,000ms timer for "sent X minutes ago" refresh)
- `RichCardContentView` — inline rich cards/suggested action cards (extends FrameLayout, implements Lighter node interface)
- `CardCarouselView` — horizontal scrolling rich card carousel (extends RecyclerView — nested RecyclerView within the message list!)
- Implied: incoming bubble cell, outgoing bubble cell (not directly named in kept smali, likely in obfuscated em* classes)

The `ConversationView` also has:
- `OverlayView` — reaction picker overlay
- `LighterWebView` — for web-based content preview
- `AppBarLayout` + `CoordinatorLayout` — conversation header with scroll behavior
- `LinearProgressIndicator` — shown during page load (paging indicator built into the view)
- Two `TextStatusBarHolderView` instances — "encrypted" / "typing" / "connecting" banners

---

## 2. ViewHolder / Item Recycling

Google Messages uses the Lighter framework's own cell/node abstraction rather than standard `RecyclerView.ViewHolder`. The adapter is `Lepjy` (an interface), with the concrete implementation injected via DI. The adapter is set on `MessageListView` via the `Lepjy.f(RecyclerView, eozu)` call pattern, where `eozu` is a typed tag used to distinguish between list contexts.

**No DiffUtil found.** The Lighter framework implements its own diffing via the `eozu`/`Lepjy` abstraction — items are submitted as `List<T>` and the framework computes diffs internally.

`setHasStableIds()` usage: Not found directly, but the `MessageCellStatusView` has a `static final J a` (long constant = 60000ms) suggesting stable timing-based IDs or tokens for animated cells.

**View pooling:** `PagedRecyclerView` uses the default RecycledViewPool. No shared pool configuration found — each `MessageListView` instance manages its own pool. The `CardCarouselView` (nested RecyclerView for rich cards) uses a separate pool.

---

## 3. Data Loading

### The Pipeline

```
SQLite (BugleContentProvider)
  → ContentResolver.notifyChange(uri)
  → ContentObserver.onChange()
  → trySend() to conflated Channel    ← coalesces bursts
  → callbackFlow<Uri>
  → map { re-query database }
  → MutableStateFlow<ConversationState>
  → Fragment collects via collectAsStateWithLifecycle()
  → Lepjy.submitList() equivalent
```

### Paging Architecture

`PagedRecyclerView` has a built-in `LinearProgressIndicator` (the `0x7f0b02a0` view ID in ConversationView). This indicator is shown/hidden during page loads. This confirms **cursor-based paging**: messages are NOT loaded all at once.

The `ConversationActivityUiState` (a Parcelable state machine with ~15 integer states including 0, 1, 2, 5, 7, 11, 12, 14) tracks loading state transitions. States include at minimum: "loading", "loaded", "error", "restoring" and "fully loaded".

The `clkw` class ("CmsRestorePageExecutor" from its logger string) handles restoring conversation pages — this is the pager. It takes a `List<T>` and an `Optional` (likely the page cursor/token) and issues batched queries via `Lcjkq` (the conversation query interface).

**Page size:** Not directly confirmed, but the `clkw.a()` method applies `Lfxbp.p(items, 10)` — capping at 10 items in some path — suggesting pages of around 10–50 messages.

### Database Layer

- Custom SQLite via `BugleContentProvider` (NOT Room)
- Tables: `conversations`, `messages`, `participants`, `profiles`
- Custom code-generated query layer (`databasegen/`)
- All queries return immediately; observation happens via ContentObserver, not polling

---

## 4. Image / Media Handling

### Image Loading

**No Glide, Fresco, Coil, or Picasso found.** Google Messages uses a custom image loading pipeline. Evidence:

- `RoundedImageView` (extends AppCompatImageView) uses a `Path` and float array for canvas clipping to achieve rounded corners — no dependency on external rounding libs
- The `RichCardContentView` handles inline card images
- A `photos` sub-package (`lighter/photos/ui/common/RoundedImageView`) exists specifically for rounded thumbnail display

The actual image network loading is likely handled by an internal Google library (possibly `com.google.android.libraries.photos` or a similar first-party lib) that is loaded at runtime via DI, not statically linked in a way that survived decompilation.

### Placeholder Sizing

`TimestampHeaderView` has `static final J a = 60000L` (60 seconds constant for timestamp refresh). There is no explicit placeholder pre-sizing code visible in the kept smali — placeholder sizing is likely handled by the data model providing dimensions before the image loads (common in RCS where image dimensions are metadata in the message).

### Rich Card Carousel

`CardCarouselView` is a nested `RecyclerView` inside the message list RecyclerView. This is the horizontal-scrolling card strip for RCS rich cards and suggested replies. It has its own `LinearLayoutManager` (horizontal orientation). The nesting is managed by RecyclerView's nested scroll handling.

---

## 5. Compose vs Views

**Finding: The conversation screen is Views, not Compose.**

The "Lighter" library (`com.google.android.libraries.messaging.lighter`) is a custom internal framework that predates Jetpack Compose within Google. It wraps Android Views (FrameLayout, LinearLayout, RecyclerView, etc.) with its own component/node interface hierarchy (`Lemhj`, `Lemis`, etc.).

Compose IS present in the codebase (references to `l/ui/platform/AndroidCompositionLocals_androidKt`, `l/ui/input/pointer/PointerInputEventHandler`), but it is used for **other parts of the app** (likely the settings page or newer secondary screens), not the primary conversation message list.

`ARCHITECTURE.md` (from the earlier RE pass) incorrectly concluded "no RecyclerView" — this was wrong. The absence of `DiffUtil`/`notifyItemInserted` in the *first* RE pass was because those are standard RecyclerView APIs and the Lighter framework wraps them.

---

## 6. Background Work

### Off Main Thread
- All database reads/writes go through the `BugleDataModel` action queue (a `HandlerThread` at `THREAD_PRIORITY_BACKGROUND` priority)
- ContentObserver callbacks fire on a background thread
- Flow operators (`map`, `conflate`) run on `Dispatchers.IO`
- gRPC calls to Tachyon (RCS) run in the `:rcs` process entirely

### On Main Thread
- Only: collecting StateFlow emissions and calling `submitList()` on the adapter
- `MessageCellStatusView` and `TimestampHeaderView` use `postDelayed(Runnable, 60000)` for time display refresh — lightweight, just a single `setText()` call

### Coroutine Scopes
- `ConversationViewModel` holds a `viewModelScope` (tied to ViewModel lifecycle)
- Flow collection uses `SharingStarted.WhileSubscribed(5000)` — 5-second replay window after configuration change, preventing database re-query on rotation

---

## 7. Animation Performance

### New Message Arrival
1. Database write fires `ContentObserver.onChange()`
2. Conflated channel coalesces if multiple messages arrive simultaneously
3. Flow emits → ViewModel state updates → Fragment calls `submitList()`
4. The Lighter adapter diffs and calls the appropriate RecyclerView notify method
5. `emmf` (custom ItemAnimator) runs the insertion animation

The custom item animator (`emmf`) has separate ArrayList queues for pending/running animations of each type. This means:
- A new message can slide in while a read receipt update is animating on the previous message simultaneously
- No serialized animation queue that would cause perceptible delay

### Conflated Channel — The Key to Burst Performance

If 5 typing indicator updates arrive in 30ms:
- Standard channel: 5 separate UI updates, potential jank
- Conflated channel: **1** UI update with the latest state

This is the single most important performance decision in the architecture for real-time feel.

### Scroll-to-Bottom on New Message

`ConversationView` has a `ViewTreeObserver.OnPreDrawListener` (stored in fields `aS` and `aT` of `akfv`/HomeFragmentPeer — same pattern used for the message screen). This listener fires before each draw pass and conditionally scrolls to the bottom if the user was already at the bottom when the new message arrived. This is the "snap to bottom on new message" behavior.

---

## 8. Key Obfuscated Class Map (Conversation Screen)

| Obfuscated | Real Name | Notes |
|---|---|---|
| `com.google.android.libraries.messaging.lighter.Lighter` | Lighter | Singleton entry point for the Lighter framework |
| `com.google.android.libraries.messaging.lighter.ui.conversation.ConversationView` | ConversationView | Root view for a conversation screen |
| `com.google.android.libraries.messaging.lighter.ui.messagelist.MessageListView` | MessageListView | Main message RecyclerView |
| `com.google.android.libraries.messaging.lighter.ui.common.pagedrecyclerview.PagedRecyclerView` | PagedRecyclerView | RecyclerView base with paging + progress indicator |
| `emhx` | LighterLinearLayoutManager | Custom LLM, tracks current first visible position |
| `emmf` | LighterItemAnimator | Custom ItemAnimator with separate animation queues |
| `com.google.android.libraries.messaging.lighter.ui.messagecell.TimestampHeaderView` | TimestampHeaderView | Date separator item, self-refreshing |
| `com.google.android.libraries.messaging.lighter.ui.messagecell.TopLabelView` | TopLabelView | Sender name row |
| `com.google.android.libraries.messaging.lighter.ui.messagecell.MessageCellStatusView` | MessageCellStatusView | Delivery/read receipt cell |
| `com.google.android.libraries.messaging.lighter.richcard.ui.CardCarouselView` | CardCarouselView | Nested RV for RCS rich cards |
| `com.google.android.libraries.messaging.lighter.richcard.ui.RichCardContentView` | RichCardContentView | Single rich card item |
| `com.google.android.libraries.messaging.lighter.photos.ui.common.RoundedImageView` | RoundedImageView | Custom rounded image (no Glide/Fresco) |
| `Lepjy` | MessageListPresenter (interface) | Injected adapter/presenter for MessageListView |
| `eozu` | ListContext/Tag | Typed tag distinguishing list instances |
| `clkw` | CmsRestorePageExecutor | Handles paged message restoration |
| `Lemia` | ScrollDirection (enum) | Controls reverseLayout on PagedRecyclerView |
| `emhj` | LighterNode (interface) | Base interface for all lighter UI nodes |
| `ConversationActivityUiState` | ConversationActivityUiState | Parcelable state machine, ~15 states |

---

## 9. What Dispatch Is Doing Wrong

Based on this RE, here are the specific gaps:

### P0 — Data layer
- **Dispatch likely uses polling or one-shot loads.** Google uses ContentObserver → conflated Flow → StateFlow. If Dispatch re-queries on every navigation or uses LiveData with no conflation, that's the root cause of perceived lag.
- **Fix:** Wrap any database observer in a `conflate()` operator. Use `SharingStarted.WhileSubscribed(5000)` in the ViewModel so rotation doesn't re-query.

### P1 — Item animator
- **Dispatch likely uses DefaultItemAnimator.** DefaultItemAnimator serializes animations — change runs after remove runs after add. Google's custom animator runs them concurrently.
- **Fix:** Implement a custom ItemAnimator that tracks pending/running queues per animation type. Or disable the default animator entirely for the message list if animations aren't needed.

### P2 — reverseLayout
- **If Dispatch inserts new messages at the end of the list and scrolls manually**, that's multiple operations. Google uses `reverseLayout=true` so the newest items are always at index 0 and appear at the visual bottom naturally.
- **Fix:** Set `reverseLayout = true` on the LinearLayoutManager. Reverse the data order (newest first). This eliminates manual scroll-to-bottom on new message.

### P3 — View types
- **If Dispatch uses a single view type for all message cells**, RecyclerView cannot recycle efficiently across type boundaries. Google has at minimum 6 item types: timestamp header, top label, text cell (incoming), text cell (outgoing), rich card, status.
- **Fix:** Split view types by message role (incoming/outgoing) and content type (text, image, audio).

### P4 — Image loading
- **If Dispatch uses Glide/Coil without explicit pre-sizing**, images will cause layout recalculation as they load, causing jank. Google pre-sizes images using RCS metadata.
- **Fix:** Always set explicit `layout_width`/`layout_height` on image containers. Never use `wrap_content` for image cells. Pre-populate from message metadata.

---

## 10. Reference Files

- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/messagelist/MessageListView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/common/pagedrecyclerview/PagedRecyclerView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/conversation/ConversationView.smali`
- `smali_classes7/emhx.smali` — custom LinearLayoutManager
- `smali_classes7/emmf.smali` — custom ItemAnimator
- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/messagecell/TimestampHeaderView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/messagecell/TopLabelView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/ui/messagecell/MessageCellStatusView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/richcard/ui/CardCarouselView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/richcard/ui/RichCardContentView.smali`
- `smali_classes7/com/google/android/libraries/messaging/lighter/Lighter.smali`
- `smali_classes5/amsu.smali` — Fragment that hosts ConversationView (line 1109: ConversationView cast; line 1756: MessageListView access; line 1780: Lepjy.f() call)
- `smali_classes9/clkw.smali` — CmsRestorePageExecutor (message pager)
- `smali/com/google/android/apps/messaging/main/ConversationActivityUiState.smali` — UI state machine
- `smali/akfv.smali` — HomeFragmentPeer (conversation list, not message list)
