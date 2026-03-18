# Google Messages Accessibility Map

**Package:** `com.google.android.apps.messaging`
**Main Activity:** `ConversationListActivity`
**UI Framework:** Mix of traditional Android Views (conversation list) and Jetpack Compose (conversation view)
**Captured:** 2026-03-08 from Pixel 9 Pro XL (Android 15)
**Dumps:** 4 clean captures (conv-list 245KB, thread-view 173KB, compose 84KB, search/list 245KB)
**Verified:** All dumps from `com.google.android.apps.messaging`, no contamination from Dispatch app

---

## Screen 1: Conversation List

**How to get here:** Launch `ConversationListActivity` via intent or press Back from a conversation.

### Structure

```
action_bar_root (FrameLayout)
└── content (FrameLayout)
    └── conversation_and_compose_container_full_view (ViewGroup)
        ├── conversation_root_container (ViewGroup)  ← empty when on list
        │   └── fragment_container (ViewGroup)
        └── conversation_list_root_container (ViewGroup)
            └── home_fragment_container (ViewGroup)
                └── home_fragment (ScrollView)
                    ├── mini_cdp_fragment (FrameLayout)  ← empty, 0px height
                    ├── toolbarLayout (LinearLayout)
                    │   └── toolbar (ViewGroup)
                    │       ├── lockup_gm (ViewGroup, desc="Google Messages")
                    │       │   ├── logo (ImageView)
                    │       │   └── product_name (TextView, text="Messages")
                    │       ├── action_zero_state_search (Button, CLICKABLE)
                    │       │   desc="Search messages"
                    │       └── selected_account_disc (FrameLayout, CLICKABLE)
                    │           desc="Signed in as Nigel Whaley..."
                    ├── gk_tooltip_hack (View)  ← ignore
                    ├── list (RecyclerView, SCROLLABLE)
                    │   desc="Conversation list"
                    │   └── [conversation items...]
                    ├── fab_stub (LinearLayout)
                    │   ├── penpal_fab (FAB, CLICKABLE, desc="Gemini")
                    │   └── start_chat_fab (Button, CLICKABLE, desc="Start chat")
                    └── bottom_layout (FrameLayout)
```

### Key Nodes

| Node | ID | Type | Action | How to find |
|------|-----|------|--------|-------------|
| Search button | `action_zero_state_search` | Button | CLICK | `findNodeByResourceId("action_zero_state_search")` or `desc="Search messages"` |
| Account/profile | `selected_account_disc` | FrameLayout | CLICK | `desc` starts with "Signed in as" |
| Conversation list | `list` | RecyclerView | SCROLL | `desc="Conversation list"` and `isScrollable` |
| Start new chat | `start_chat_fab` | Button | CLICK | `desc="Start chat"` |
| Gemini FAB | `penpal_fab` | FAB | CLICK | `desc="Gemini"` |

### Conversation Item Structure

Each conversation in the list follows this pattern:

```
swipeableContainer (ViewGroup, CLICKABLE)  ← tap to open conversation
├── contact_avatar_view (FrameLayout, desc="Conversation Icon", CLICKABLE)
│   ├── conversation_icon (ImageView)
│   └── avatar_badge_icon? (FrameLayout)  ← only if RCS
│       └── rcs_badge (ImageView)
├── conversation_text_content (RelativeLayout)
│   ├── conversation_name (TextView)     ← contact/group name
│   └── conversation_snippet (TextView)  ← last message preview
└── timestamp_and_sub_icons_container (LinearLayout)
    ├── attachment_and_timestamp (FrameLayout)
    │   └── conversation_timestamp_and_unread_badge (LinearLayout)
    │       └── conversation_timestamp (TextView)  ← e.g. "21:59"
    └── conversation_sub_icons (LinearLayout)
        └── unread_badge_view_with_message_count_stub? (TextView)  ← unread count
```

### How to target a conversation

1. **By contact name:** Search `conversation_name` (TextView, id=`conversation_name`) for matching text
2. **By snippet:** Search `conversation_snippet` for message text
3. **To open:** CLICK on the `swipeableContainer` parent (id=`swipeableContainer`, isClickable=true)
4. **To check unread:** Look for `unread_badge_view_with_message_count_stub` — its text is the count

### Scrolling the list

- The `list` (RecyclerView) is SCROLLABLE
- Scroll with `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`
- Items load dynamically as you scroll (RecyclerView virtualization)

---

## Screen 2: Conversation Thread (Messages View)

**How to get here:** Tap a conversation item from the list, or navigate via search.

### Structure

```
action_bar_root (FrameLayout)
└── content (FrameLayout)
    └── conversation_and_compose_container_full_view (ViewGroup)
        ├── conversation_list_root_container (ViewGroup)  ← empty/background
        └── conversation_root_container (ViewGroup)
            └── fragment_container (ViewGroup)
                └── ConversationScreenUi (View, Compose)
                    ├── message_list (View, SCROLLABLE)
                    │   └── [message items...]
                    ├── [compose bar at bottom]
                    └── top_app_bar (View)
```

### Top App Bar

```
top_app_bar (View)
├── Back button (View, CLICKABLE, desc="Back")
├── top_app_bar_title_row (View, CLICKABLE)  ← tap for contact info
│   ├── monogram_test_tag / GlideMonogram (View)  ← avatar
│   └── TextView (text = contact name, e.g. "Blaker Man")
├── Call button (View, CLICKABLE, desc="Call")
├── Video button (View, CLICKABLE, desc="Video")
└── More button (View, CLICKABLE, desc="More")
```

### Message Item Structure (Jetpack Compose)

Each message in the thread:

```
View (CLICKABLE)  ← message container, tap for reactions/menu
├── text_separator? (View)  ← date/time divider, only between groups
│   └── message_text (TextView, text="Thursday • 15:11")
└── View (CLICKABLE)  ← message bubble
    └── message_text (TextView)
        text = actual message content
        desc = full context: "{Sender} said  {text} {day} {time} ."
```

**Key observations:**
- `message_text` (id=`message_text`) holds both message content AND date separators
- The `desc` (contentDescription) contains sender + text + timestamp in one string
- Pattern: `"{ContactName} said  {message} {Day} {HH:mm} ."`
- Your own messages: `"You said  {message} {Day} {HH:mm} ."`
- Date separators have `message_text` with patterns like `"Thursday • 15:11"` or `"23:29"`
- "Unread" marker appears as a standalone text node between messages

### How to parse messages

1. Find the `message_list` (SCROLLABLE, id=`message_list`)
2. Iterate through children looking for nodes with id=`message_text`
3. **Filter out date separators:** Text matches pattern `"Day • HH:mm"` or is a bare time like `"23:29"`
4. **Determine sender:** Parse `contentDescription`:
   - Starts with `"You said"` → outgoing
   - Starts with `"{Name} said"` → incoming from that contact
5. **Extract timestamp:** From `desc`, last part before the final `.`
6. **Detect unread boundary:** Text node with `text="Unread"`

### Scrolling messages

- `message_list` is SCROLLABLE
- `ACTION_SCROLL_FORWARD` scrolls down (newer messages)
- `ACTION_SCROLL_BACKWARD` scrolls up (older messages)
- Messages load dynamically on scroll
- Most recent messages are at the BOTTOM

### Compose Bar

```
[Bottom of message_list]
├── ComposeRowIcon:Shortcuts (View, CLICKABLE)
│   └── desc="Show attach content screen"           ← ATTACHMENT BUTTON
├── compose_message_text (EditText, CLICKABLE, EDITABLE)
│   text="RCS message" or "Text message"             ← COMPOSE FIELD
├── ComposeRowIcon:Emotive (View, CLICKABLE)
│   └── desc="Show attach emoji and stickers screen" ← EMOJI BUTTON
├── ComposeRowIcon:Gallery (View, CLICKABLE)
│   └── desc="Show attach media screen"              ← GALLERY/PHOTO BUTTON
└── Compose:Draft:Send (View, CLICKABLE)
    └── desc changes based on state:
        - Empty field: "Show record a voice message screen"  ← VOICE
        - Has text: "Send SMS" or "Send message"             ← SEND BUTTON
```

### How to send a message

1. Find `compose_message_text` (EditText, id=`compose_message_text`, isEditable)
2. Use `ACTION_SET_TEXT` with Bundle:
   ```
   ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE = "your message"
   ```
3. Wait ~800ms for UI to update
4. Find send button: `Compose:Draft:Send` (View, id=`Compose:Draft:Send`)
   - Its `desc` changes from "Show record a voice message screen" to "Send SMS"/"Send message"
5. CLICK or gesture tap the send button

### How to attach content

| Button | ID | Description | What it opens |
|--------|-----|-------------|---------------|
| Attachments | `ComposeRowIcon:Shortcuts` | "Show attach content screen" | Content picker (files, location, contact) |
| Gallery | `ComposeRowIcon:Gallery` | "Show attach media screen" | Photo/video picker |
| Emoji | `ComposeRowIcon:Emotive` | "Show attach emoji and stickers screen" | Emoji/sticker picker |

---

## Screen 3: New Conversation / Compose

**How to get here:** Tap "Start chat" FAB, or `ACTION_SENDTO` with `smsto:` URI.

Same as Thread View but with empty message list and potentially a contact picker at top.

---

## Screen 4: Search

**How to get here:** Tap `action_zero_state_search` button on conversation list.

### Expected structure (from code analysis)

```
[Search toolbar]
├── Search input (EditText, isEditable)  ← type contact/message search
├── Back/close button
└── [Search results list]
    └── [Result items similar to conversation items]
```

**To search:**
1. Tap `action_zero_state_search` (CLICK)
2. Wait for search field to appear (~1500ms)
3. Find editable field (`findComposeField()` — looks for `isEditable`)
4. `ACTION_SET_TEXT` with search query
5. Wait for results (~2000ms)
6. Find matching result by contact name text
7. CLICK result to open conversation

---

## Key Resource IDs Reference

### Conversation List Screen
- `action_zero_state_search` — Search button
- `list` — RecyclerView (conversation list, scrollable)
- `swipeableContainer` — Individual conversation row (clickable)
- `conversation_name` — Contact/group name
- `conversation_snippet` — Last message preview
- `conversation_timestamp` — Time of last message
- `unread_badge_view_with_message_count_stub` — Unread count badge
- `conversation_icon` — Contact avatar
- `rcs_badge` — RCS indicator
- `start_chat_fab` — New chat FAB
- `product_name` — "Messages" title text

### Conversation Thread Screen
- `ConversationScreenUi` — Root of Compose UI
- `message_list` — Message container (scrollable)
- `message_text` — Message content AND date separators
- `text_separator` — Date/time divider between message groups
- `compose_message_text` — Text input field (editable)
- `ComposeRowIcon:Shortcuts` — Attachment button
- `ComposeRowIcon:Gallery` — Photo/media button
- `ComposeRowIcon:Emotive` — Emoji button
- `Compose:Draft:Send` — Send/voice button
- `top_app_bar` — Header bar
- `top_app_bar_title_row` — Contact name area (clickable for info)
- `GlideMonogram` — Contact avatar in header

### Common Patterns
- `desc="Back"` — Back button (any screen)
- `desc="Call"` — Phone call button (thread view)
- `desc="Video"` — Video call button (thread view)
- `desc="More"` — Overflow menu (thread view)
- `desc="Search messages"` — Search button (list view)

---

## Automation Recipes

### Read messages from a contact

```
1. Launch ConversationListActivity
2. Search conversation_name nodes in the list RecyclerView
3. If found: CLICK the swipeableContainer parent
4. If not found: CLICK action_zero_state_search → type in search → tap result
5. Wait for ConversationScreenUi to appear
6. Find message_list (SCROLLABLE)
7. Extract message_text nodes, filtering out text_separator children
8. Parse contentDescription for sender/timestamp
```

### Send a message to existing contact

```
1. Navigate to conversation (same as read)
2. Find compose_message_text (EditText)
3. ACTION_SET_TEXT with message body
4. Wait 800ms
5. Find Compose:Draft:Send and check desc changed to "Send SMS"/"Send message"
6. CLICK send button
```

### Send to new number

```
1. am start -a android.intent.action.SENDTO -d "smsto:{number}"
2. Wait for ConversationScreenUi
3. Find compose_message_text → ACTION_SET_TEXT
4. Wait → Find send button → CLICK
```

### Check for unread messages

```
1. Open ConversationListActivity
2. Scan swipeableContainer items in list RecyclerView
3. For each: check for unread_badge_view_with_message_count_stub child
4. If present: text = unread count, conversation_name = who sent them
```

### Send attachment

```
1. Navigate to conversation
2. CLICK ComposeRowIcon:Shortcuts (desc="Show attach content screen")
3. [Need to map the attachment picker screen]
4. Select file/content
5. CLICK send button
```

---

## RCS vs SMS Detection

- RCS conversations show `rcs_badge` (ImageView) inside `avatar_badge_icon`
- Compose field text: `"RCS message"` (RCS) vs `"Text message"` (SMS/MMS)
- Send button desc: `"Send message"` (RCS) vs `"Send SMS"` (SMS)

---

## Notes

- The conversation list uses traditional Android Views (RecyclerView, ViewGroup, TextView)
  → Resource IDs are reliable, text is directly accessible
- The conversation thread uses Jetpack Compose
  → Many nodes are generic "View" with no resource ID
  → `contentDescription` is the primary way to identify elements
  → `findAccessibilityNodeInfosByText()` system API works well here
- The compose field is always `EditText` with id=`compose_message_text`, even in Compose
- Message content always uses id=`message_text`, making it easy to filter
- The `desc` on messages follows a strict pattern that can be parsed programmatically
