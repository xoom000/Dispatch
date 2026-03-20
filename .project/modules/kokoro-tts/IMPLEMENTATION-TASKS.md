# Kokoro TTS Preprocessing — Implementation Tasks

**Objective:** Stop agents from reading markdown, file paths, and code syntax aloud. Clean all text before it reaches Kokoro TTS.

**What "done" looks like:** Nigel hears clean spoken English from every agent. No asterisks, no file paths, no backticks, no UUIDs, no git hashes. Code blocks are silently skipped. File paths say just the filename. Bold/italic text is spoken without formatting markers. Bullet lists become natural sentences.

---

## Deliverables

### 1. Create `file_bridge/tts_preprocessor.py`

New Python module. Pure stdlib + `re`. Contains:

- `preprocess_for_tts(text, max_length=800, naturalize_identifiers=True) -> str`
- Strips: bold/italic markers, headers, blockquotes, horizontal rules, HTML tags, emoji
- Removes: code blocks entirely, JSON blobs, import statements, decorators
- Converts: bullet lists → sentences, markdown links → link text only, tables → skip
- Handles: file paths → filename only (or friendly prefix label), URLs → domain only
- Naturalizes: camelCase → "camel case", snake_case → "snake case", CONSTANT → lowercase
- Skips entirely: git hashes, UUIDs, line number references
- Post-processes: ensures sentence-ending punctuation, splits long sentences at commas, collapses whitespace, truncates to max_length

### 2. Hook into File Bridge TTS endpoints

In `file_bridge/routes/tts.py` (or wherever `/api/tts` and `/api/tts/stream` are defined):

- Import `preprocess_for_tts`
- Call it on `request.text` BEFORE forwarding to Kokoro
- Use `max_length=800` for non-streaming, `max_length=600` for streaming
- Return 204 if preprocessed text is empty (nothing speakable)

### 3. Test with real agent output

Before/after examples that MUST work:

| Before (raw agent text) | After (what Nigel hears) |
|---|---|
| `**Fixed** the bug in \`cli-beautified.js\` at line 401407` | `Fixed the bug in cli-beautified.js` |
| `The file at /home/xoom000/ClaudeCode/dissected/cli-beautified.js has 520,715 lines` | `The file in the dissected directory has 520,715 lines` |
| ````\n```python\ndef foo():\n    pass\n```\n```` | *(silence — code block removed)* |
| `- Fixed avatar initials\n- Updated timestamps\n- Added unread badges` | `Fixed avatar initials. Updated timestamps. Added unread badges.` |
| `See [the docs](https://developer.android.com/ai/appfunctions) for details` | `See the docs for details` |
| `Commit ab3f7c2d fixed the issue` | `Fixed the issue` |

---

## Files to Touch

1. **CREATE:** `file_bridge/src/file_bridge/tts_preprocessor.py` — the preprocessing module
2. **EDIT:** The file containing `/api/tts` and `/api/tts/stream` route handlers — add import + call
3. **No Android changes needed** — this is purely server-side

## Key Constraints

- Kokoro has NO SSML support — all cleanup must be text transformation
- Keep it fast — this runs on every TTS request, before audio generation
- Don't strip everything — the text should still be meaningful, just not noisy
- The `dispatch send` CLI messages from agents are the primary source of noisy text
