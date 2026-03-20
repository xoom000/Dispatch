# Dispatch vs Google Messages — UI Audit

**Date:** 2026-03-19
**Author:** Cipher
**Source:** Screenshots from Nigel's Pixel 9 + Dispatch source code analysis

---

## The Problem

Dispatch's conversation list shows raw UUIDs, colored circles with meaningless initials, no message previews, no unread badges. Google Messages shows contact names, profile photos, last message text, unread counts, smart timestamps. The gap is massive but fixable.

---

## Conversation List (ChatScreen.kt)

### What Google Messages Does

- **Contact name** as the primary text (bold, large)
- **Last message preview** below the name (gray, truncated to 1 line)
- **Profile photo** OR colored circle with first letter of NAME
- **Smart timestamp**: "13:08" for today, "Wed" for this week, "Mar 12" for older
- **Unread badge**: green circle with count
- **Unread styling**: bold name + bold preview for unread conversations
- **Chat indicators**: RCS bubble, typing indicator, read receipts
- **Group avatars**: stacked circles for group conversations

### What Dispatch Does (Source: ChatScreen.kt lines 101-148)

- **Session title** = `summary?.take(60) ?: alias.ifBlank { sessionId.take(20) }` — summary is usually null, alias is often blank, falls through to UUID
- **Department name** below the title (green text) — "Research", "Cipher", "Home"
- **AgentAvatar** with initials from the label — when label is a UUID like "93f6f144", initials become "93" which is meaningless
- **Timestamp** formatted as "HH:mm" only — no day context, no "Yesterday", no smart formatting
- **No message preview** — only shows department/status
- **No unread count** — not tracked at all
- **No unread styling** — all rows look identical

### Fixes Needed (Conversation List)

1. **Fix session naming** — Department name should be the PRIMARY label, not the UUID. Show "Research" or "Cipher" as the big text, session alias as subtitle
2. **Add last message preview** — Server already returns this data (summary field). Surface it as gray preview text
3. **Smart timestamps** — Today shows time, yesterday shows "Yesterday", this week shows day name, older shows date
4. **Unread count tracking** — Needs server-side support (track read position per session) OR client-side tracking (last-seen message ID per session in DataStore)
5. **Unread bold styling** — Bold the title and preview for sessions with unread messages
6. **Fix AgentAvatar initials** — Pass department name to AgentAvatar, not the UUID. "Research" → "RE", "Cipher" → "CI", "Engineering" → "EN"

---

## Conversation View (MessagesScreen.kt)

### What Google Messages Does

- **Header**: back arrow, contact name, call button, video button, overflow menu
- **Message bubbles**: rounded corners, teal/green for sent, dark gray for received
- **Date separators**: "Yesterday · 08:08" centered between messages
- **Unread divider**: "── Unread ──" line showing where you left off
- **Timestamps**: on individual messages (small, below bubble)
- **Read receipts**: checkmarks for sent status
- **Input bar**: "RCS message" placeholder, attachment (+), emoji, image picker, voice
- **Encryption indicator**: lock icon on timestamps

### What Dispatch Does

- **Header**: back arrow, department name, call button, overflow — mostly right
- **Message bubbles**: themed colors per sender type (Nigel, Agent, Dispatch, Tool) — actually good
- **Message formatter**: handles bold, italic, code, links, @mentions — exists but basic
- **JumpToBottom**: implemented — good
- **Input bar**: has +, emoji, image, mic, send — icons exist but NOTHING IS WIRED
- **No date separators** between messages from different days
- **No read receipts**

### Fixes Needed (Conversation View)

1. **Date separators** — Insert "Today", "Yesterday", date headers between message groups
2. **Wire the input bar buttons**:
   - **Image picker** (Photo icon): launch image picker, POST to File Bridge, display in conversation
   - **File attachment** (+): launch file picker, upload, show as attachment bubble
   - **Mic** (already planned for voice input)
   - **Emoji**: show emoji keyboard
3. **Sender grouping improvements** — already has some, but could batch consecutive messages from same sender tighter

---

## File Transfer Architecture

### What's Needed

1. **Server endpoint**: POST /api/sessions/{id}/upload — accept multipart file, store, return URL
2. **Android**: image/file picker intent → multipart upload → display in chat
3. **Display**: attachment bubble type (image preview for photos, file icon + name for documents)
4. **Download**: tap attachment → download + open with system viewer

### What Already Exists

- File Bridge already has upload infrastructure (it handles TTS audio files)
- OkHttp client is configured with multipart support in the Retrofit interface
- The `+` and image buttons exist in the input bar — just not wired

---

## Priority Fixes (Effort vs Impact)

### Quick Wins (< 1 hour each)

1. **Fix avatar initials** — Pass department to AgentAvatar instead of UUID. 1 line change in ChatScreen.kt
2. **Make department the primary label** — Swap title/subtitle in SessionRow. Department big and bold, alias/summary small
3. **Smart timestamps** — Replace formatTimestamp() with a function that handles today/yesterday/day logic

### Medium (2-4 hours each)

4. **Last message preview** — Add a `lastMessage` field to the for-dispatch API response, surface in SessionRow
5. **Date separators in conversation** — Insert date headers in the LazyColumn based on message timestamps
6. **Unread tracking** — Save last-read message ID per session in DataStore, show badge on list

### Larger (4-8 hours each)

7. **Image sharing** — File picker → upload → display pipeline
8. **File attachments** — General file upload/download
9. **Wire emoji button** — Show system emoji keyboard on tap

---

## The One-Line Summary

Dispatch has the bones of a real messaging app — themed bubbles, streaming, markdown formatting, JumpToBottom. But the conversation LIST is showing raw infrastructure (UUIDs, meaningless avatars) instead of human-readable information. The conversation VIEW input bar has all the icons but nothing wired. Three quick fixes (avatar, labels, timestamps) would make the list look 80% better immediately.
