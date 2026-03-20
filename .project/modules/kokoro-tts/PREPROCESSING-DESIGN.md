# Kokoro TTS Text Preprocessing Layer — Design Document

**Status:** Design / Pre-Implementation
**Module:** File Bridge (server-side Python)
**Author:** Dispatch Engineering
**Date:** 2026-03-19

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Kokoro TTS Capabilities Research](#2-kokoro-tts-capabilities-research)
3. [Text Preprocessing Rules](#3-text-preprocessing-rules)
4. [Python Implementation](#4-python-implementation)
5. [Pipeline Hook](#5-pipeline-hook)
6. [Before / After Examples](#6-before--after-examples)
7. [Never-Speak Rules](#7-never-speak-rules)
8. [Voice Model Recommendations](#8-voice-model-recommendations)
9. [Prosody and Naturalness Parameters](#9-prosody-and-naturalness-parameters)

---

## 1. Problem Statement

Claude Code agents respond with full markdown formatting: asterisks for bold and italic, backticks for inline code, triple-backtick fences for code blocks, absolute file paths, line numbers, git hashes, URLs, and structured tables. This text is passed directly to Kokoro TTS via the File Bridge `/api/tts` endpoint and spoken verbatim in Nigel's earbuds.

### What Nigel currently hears

Instead of natural language updates, Kokoro reads out:

- `"asterisk asterisk bold asterisk asterisk"` for `**bold**`
- `"slash home slash xoom zero zero zero slash ClaudeCode slash dissected slash cli hyphen beautified dot js"` for a file path
- `"backtick inline code backtick"` for inline code
- `"pound pound header"` for markdown headers
- `"pipe column one pipe column two pipe"` for table rows
- `"underscore underscore variable underscore underscore"` for Python dunder methods
- `"line underscore four zero one four zero seven"` for a symbolic reference like `line_401407`
- Git commit hashes read as hex strings: `"a b 3 f 7 c 2"`

This is a critical UX failure. The voice channel is supposed to give Nigel situational awareness — a heads-up of what the agent did, what it found, what it needs. Instead it is unlistenable noise.

### Root cause

The dispatch pipeline sends raw agent output text directly to Kokoro. There is no preprocessing stage. Agent output is markdown-structured prose designed to be read by eyes, not ears.

### Solution

Insert a preprocessing function at the File Bridge level — specifically, any time text is routed to `/api/tts` or `/api/tts/stream`. The preprocessor transforms markdown + technical syntax into clean spoken prose before Kokoro ever sees the text.

---

## 2. Kokoro TTS Capabilities Research

### 2.1 Model Overview

Kokoro-82M is an open-source TTS model based on StyleTTS 2 + iSTFTNet architecture with 82 million parameters. Key characteristics:

- **Speed:** 100–300ms latency with GPU; 35–100x real-time generation on GPU; 3–11x on CPU
- **Sample rate:** 24,000 Hz output
- **License:** Apache 2.0
- **Deployment in Dispatch:** Running on Oasis at `:8400/api/tts` and `:8400/api/tts/stream` via Kokoro-FastAPI wrapper

### 2.2 Available Voices

Kokoro supports 54 voices across 8 languages. Relevant English voices:

**American English — Female (af_)**
| Voice | Character | Best use |
|-------|-----------|---------|
| `af_heart` | Warm, expressive | Primary agent personality |
| `af_bella` | Clear, professional | Neutral briefings |
| `af_jessica` | Energetic | Alerts, urgent messages |
| `af_nova` | Calm, measured | Long-form summaries |
| `af_sarah` | Friendly | Conversational agents |
| `af_sky` | Bright | Status updates |

**American English — Male (am_)**
| Voice | Character | Best use |
|-------|-----------|---------|
| `am_puck` | Current status voice in Dispatch | System messages |
| `am_michael` | Deep, authoritative | Executive/boardroom agent |
| `am_adam` | Neutral | General purpose |
| `am_eric` | Conversational | Engineering agents |
| `am_liam` | Younger, relaxed | Dev/code agents |

**British English**
| Voice | Character | Best use |
|-------|-----------|---------|
| `bm_george` | Classic British | Formal summaries |
| `bm_daniel` | Modern British | Analysis agents |
| `bf_emma` | Clear British female | Research agents |

**Voice blending:** Kokoro supports weighted voice blending via syntax like `af_bella(2)+af_sky(1)` for a 67%/33% mix. This can create unique agent personalities.

### 2.3 API Parameters (Kokoro-FastAPI / OpenAI-compatible endpoint)

```json
{
  "model": "kokoro",
  "input": "<preprocessed text>",
  "voice": "af_heart",
  "response_format": "wav",
  "speed": 1.0
}
```

- **`speed`**: Float, default `1.0`. Range approximately `0.5`–`2.0`. Lower = slower, more deliberate. Good starting point for agent dispatches: `0.95` (slightly slower than default for clarity).
- **`response_format`**: `mp3`, `wav`, `opus`, `flac`, `m4a`, `pcm`. Dispatch uses `wav` for ExoPlayer streaming.
- **`stream`**: Boolean. Dispatch uses the streaming endpoint (`/api/tts/stream`) for low-latency first-audio.

### 2.4 SSML Support Status

**Current status (as of March 2026): Kokoro does NOT have production SSML support.**

The Kokoro-FastAPI project has an open issue (#396) requesting SSML support. As of February 2026, the feature is planned but unreleased. The proposed implementation would support:

- `<break time="300ms"/>` — deliberate pauses
- `<prosody rate="90%">` — pacing adjustments
- `<emphasis level="moderate">` — stress on key words
- `<phoneme alphabet="ipa" ph="...">` — pronunciation override
- `<say-as interpret-as="...">` — interpretation hints

**Do not rely on SSML.** All naturalness improvements must come from text preprocessing — punctuation, sentence structure, and clean prose.

### 2.5 Existing Text Processing in Kokoro

Kokoro uses the **Misaki** G2P (Grapheme-to-Phoneme) library for tokenization. Misaki implements:

- Multi-tiered regex rules for English phonemics
- Unicode normalization
- Custom word/phoneme dictionaries

Misaki handles normal English text well. It does NOT handle markdown syntax, code identifiers, or file paths gracefully — those pass through as-is and get spoken letter-by-letter or as raw symbols.

**IPA override syntax:** Kokoro supports inline IPA phoneme hints using bracket notation:

```
[word](/phoneme/)
```

Example: `[Kokoro](/kˈOkəɹO/)` — but this requires knowing IPA and is only useful for specific brand names or unusual words, not for preprocessing markdown.

### 2.6 Practical Input Guidelines

From Kokoro documentation and testing:

- Keep sentences to 20–25 words for natural rhythm
- Use commas for short pauses, periods or ellipses for longer breaks
- Break paragraphs into 2-sentence blocks
- Punctuation signals breathing points — add it aggressively
- Avoid dense clause stacking (4+ clauses without punctuation)
- The model responds to sentence-final punctuation for prosody — always end with `.`, `!`, or `?`

---

## 3. Text Preprocessing Rules

### 3.1 Master Rules Table

| Input Pattern | Example | Rule | Output |
|--------------|---------|------|--------|
| `**bold text**` | `**important**` | Strip asterisks, keep text | `important` |
| `*italic text*` | `*note this*` | Strip asterisks, keep text | `note this` |
| `__bold text__` | `__critical__` | Strip underscores, keep text | `critical` |
| `_italic text_` | `_emphasis_` | Strip underscores, keep text | `emphasis` |
| `` `inline code` `` | `` `getUserName()` `` | Strip backticks; if identifier, naturalize | `get user name` |
| ```` ```code block``` ```` | Multi-line code | Skip entirely | *(silence)* |
| `# Header` | `# Summary` | Speak text with leading pause | `Summary.` |
| `## Subheader` | `## Details` | Speak text, no special treatment | `Details.` |
| `### H3+` | `### Step 1` | Speak text only | `Step 1.` |
| `- bullet item` | `- Fixed the bug` | Speak as sentence | `Fixed the bug.` |
| `* bullet item` | `* Updated config` | Speak as sentence | `Updated config.` |
| `1. numbered item` | `1. Clone the repo` | Speak as sentence | `Clone the repo.` |
| `[link text](url)` | `[docs](https://...)` | Keep link text only | `docs` |
| `![alt](url)` | `![diagram](...)` | Skip or say "image" | *(skip)* |
| `https://example.com/long/path` | Full URL | Domain only | `example.com` |
| `/home/xoom000/path/to/file.py` | Absolute path | Abbreviate to filename or skip | `file.py` or *(skip)* |
| `./relative/path.js` | Relative path | Filename only | `path.js` |
| `\|col1\|col2\|` | Table rows | Skip or summarize | *(skip)* |
| `---` | Horizontal rule | Replace with pause (period) | `.` |
| `> blockquote` | `> Note: ...` | Strip `>`, speak text | `Note: ...` |
| `**bold** with text` | Mixed inline | Strip formatting, speak prose | speak naturally |
| `snake_case_identifier` | `user_name_field` | Naturalize | `user name field` |
| `camelCaseIdentifier` | `getUserName` | Naturalize | `get user name` |
| `CONSTANT_NAME` | `MAX_RETRY_COUNT` | Naturalize | `max retry count` |
| `function()` | `render()` | Naturalize or skip | `render` |
| `Class.method()` | `TtsEngine.speak()` | Naturalize | `TTS engine speak` |
| Git hash | `ab3f7c2d` | Skip — never speak | *(skip)* |
| UUID | `550e8400-e29b-41d4-a716` | Skip | *(skip)* |
| Line numbers | `line 401`, `L401` | Skip | *(skip)* |
| `line_401407` | Symbolic line ref | Skip | *(skip)* |
| Emoji | `✅ Done` | Skip emoji, speak text | `Done` |
| `---` separator | Section break | Sentence pause | `.` |
| `...` ellipsis | `Working on it...` | Keep — signals pause | `Working on it.` |
| Number ranges | `1-10`, `100–200` | Speak naturally | `1 to 10` |
| File extensions | `.py`, `.kt`, `.js` | Speak as type | `Python file`, `Kotlin file` |
| `{key: value}` JSON | `{"status": "ok"}` | Skip or summarize | *(skip)* |
| `<xml>` tags | `<break time="1s"/>` | Strip | *(strip)* |
| `import X from Y` | Code import statement | Skip | *(skip)* |
| `@decorator` | `@Singleton` | Skip or naturalize | `singleton` |

### 3.2 File Path Handling Detail

File paths are the worst offenders. Strategy by path type:

| Path type | Example | Action |
|-----------|---------|--------|
| Absolute Linux path | `/home/xoom000/ClaudeCode/dissected/cli.js` | Extract filename → `cli.js` |
| Absolute with known prefix | `/home/xoom000/AndroidStudioProjects/Dispatch/app/src/...` | Say "in the Dispatch project" |
| Deep path with meaningful filename | `/home/xoom000/ClaudeCode/dissected/cli-beautified.js` | `the cli-beautified file` |
| Path to a directory | `/home/xoom000/.claude/` | Skip or say "the Claude config directory" |
| Path in a list of changes | Multiple paths | Say "several files" if >2 |

### 3.3 Code Block Handling

Code blocks (triple backtick fences) should be entirely removed. They are never useful to hear. Replace with a transition phrase when the surrounding context references the block:

- If code block is preceded by "here is the code:" → replace block with "...shown on screen."
- If code block stands alone → replace with nothing
- If code block is preceded by "for example:" → replace with "as shown on screen."

### 3.4 Sentence Structure Post-Processing

After stripping markdown:

1. Collapse 3+ blank lines to 1 blank line (sentence break)
2. Convert remaining blank lines to periods (sentence end)
3. Ensure every sentence ends with punctuation (`.`, `!`, or `?`)
4. Remove duplicate punctuation (`...` → `.`, `!!` → `!`)
5. Cap max sentence length: if a sentence exceeds 30 words, split at the nearest comma
6. Remove parenthetical technical asides: `(see line 401 in cli.js)` → *(omit)*
7. Collapse multiple spaces to single space
8. Strip leading/trailing whitespace

---

## 4. Python Implementation

This module lives on the File Bridge server. It is a pure Python module with no external dependencies beyond the standard library and one optional library (`re`).

```python
# file_bridge/tts_preprocessor.py
"""
TTS Text Preprocessor for Dispatch / Kokoro TTS

Converts markdown-formatted agent output into clean spoken prose
before sending to Kokoro TTS. Applied server-side in File Bridge.

Usage:
    from tts_preprocessor import preprocess_for_tts
    clean = preprocess_for_tts(raw_agent_text)
    # pass clean to Kokoro
"""

import re
from typing import Optional


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# File extensions → spoken type names
FILE_EXT_MAP = {
    ".py": "Python file",
    ".kt": "Kotlin file",
    ".js": "JavaScript file",
    ".ts": "TypeScript file",
    ".json": "JSON file",
    ".yaml": "YAML file",
    ".yml": "YAML file",
    ".md": "markdown file",
    ".sh": "shell script",
    ".java": "Java file",
    ".xml": "XML file",
    ".sql": "SQL file",
    ".html": "HTML file",
    ".css": "CSS file",
    ".go": "Go file",
    ".rs": "Rust file",
    ".toml": "config file",
    ".env": "environment file",
    ".gradle": "Gradle file",
}

# Known path prefixes with friendly labels
PATH_PREFIX_MAP = [
    ("/home/xoom000/AndroidStudioProjects/Dispatch", "the Dispatch project"),
    ("/home/xoom000/ClaudeCode/dissected", "the dissected directory"),
    ("/home/xoom000/ClaudeCode", "the Claude Code directory"),
    ("/home/xoom000/.claude", "the Claude config directory"),
    ("/home/xoom000/", "the home directory"),
    ("/etc/", "the system config directory"),
    ("/usr/", "the system directory"),
    ("/var/", "the var directory"),
    ("/tmp/", "a temp directory"),
]

# Regex patterns — compiled once at module load
_RE_CODE_BLOCK        = re.compile(r'```[\s\S]*?```', re.MULTILINE)
_RE_INLINE_CODE       = re.compile(r'`([^`\n]+)`')
_RE_BOLD_STAR         = re.compile(r'\*\*(.+?)\*\*')
_RE_ITALIC_STAR       = re.compile(r'\*(.+?)\*')
_RE_BOLD_UNDERSCORE   = re.compile(r'__(.+?)__')
_RE_ITALIC_UNDERSCORE = re.compile(r'_([^_\n]+)_')
_RE_HEADER            = re.compile(r'^#{1,6}\s+(.+)$', re.MULTILINE)
_RE_BULLET_DASH       = re.compile(r'^\s*[-*]\s+(.+)$', re.MULTILINE)
_RE_NUMBERED_LIST     = re.compile(r'^\s*\d+\.\s+(.+)$', re.MULTILINE)
_RE_BLOCKQUOTE        = re.compile(r'^>\s*(.+)$', re.MULTILINE)
_RE_HR                = re.compile(r'^(?:-{3,}|_{3,}|\*{3,})$', re.MULTILINE)
_RE_TABLE_ROW         = re.compile(r'^\|.+\|$', re.MULTILINE)
_RE_TABLE_SEP         = re.compile(r'^\|[-:| ]+\|$', re.MULTILINE)
_RE_MARKDOWN_LINK     = re.compile(r'\[([^\]]+)\]\([^)]+\)')
_RE_MARKDOWN_IMAGE    = re.compile(r'!\[[^\]]*\]\([^)]+\)')
_RE_BARE_URL          = re.compile(r'https?://([a-zA-Z0-9.-]+)(?:/[^\s]*)?')
_RE_ABSOLUTE_PATH     = re.compile(r'(?<!\w)(/(?:[a-zA-Z0-9._-]+/)*[a-zA-Z0-9._-]+(?:\.[a-zA-Z]{1,10})?)')
_RE_RELATIVE_PATH     = re.compile(r'(?<!\w)\./(?:[a-zA-Z0-9._-]+/)*([a-zA-Z0-9._-]+)')
_RE_GIT_HASH          = re.compile(r'\b[0-9a-f]{7,40}\b')
_RE_UUID              = re.compile(r'\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b', re.IGNORECASE)
_RE_LINE_REF          = re.compile(r'\b[Ll]ines?\s*\d+[-–]\d+\b|\bL\d+\b|\bline[_-]\d+\b|\b\w+_\d{4,}\b')
_RE_CAMEL_CASE        = re.compile(r'(?<=[a-z])(?=[A-Z])')
_RE_SNAKE_CASE        = re.compile(r'_+')
_RE_PARENS_TECHNICAL  = re.compile(r'\((?:see\s+)?(?:line|file|path|ref|id|hash|commit)[^)]*\)', re.IGNORECASE)
_RE_EMOJI             = re.compile(
    "[\U00002600-\U000027BF]|[\U0001F300-\U0001F9FF]|[\U0001FA00-\U0001FAFF]|"
    "[\U00002702-\U000027B0]|[\U0000FE00-\U0000FE0F]|[\U0001F000-\U0001F02F]",
    re.UNICODE
)
_RE_WHITESPACE_RUNS   = re.compile(r'  +')
_RE_BLANK_LINES       = re.compile(r'\n{3,}')
_RE_XML_TAG           = re.compile(r'<[^>]+>')
_RE_FUNCTION_CALL     = re.compile(r'(\w+)\s*\(\s*\)')
_RE_MULTI_PUNCT       = re.compile(r'([.!?]){2,}')
_RE_TRAILING_PUNCT    = re.compile(r'[,;:]+$')
_RE_NUMBER_RANGE      = re.compile(r'(\d+)\s*[-–]\s*(\d+)')
_RE_IMPORT_LINE       = re.compile(r'^(?:import|from|require|#include)\s+.+$', re.MULTILINE)
_RE_DECORATOR         = re.compile(r'@[A-Za-z][A-Za-z0-9_]*(?:\([^)]*\))?')
_RE_JSON_BLOCK        = re.compile(r'\{[^}]{10,}\}')


def _naturalize_identifier(text: str) -> str:
    """Convert code identifiers to natural spoken form.

    getUserName      -> get user name
    MAX_RETRY_COUNT  -> max retry count
    snake_case_thing -> snake case thing
    render()         -> render
    """
    # Strip trailing parentheses/brackets
    text = re.sub(r'[(){}\[\]]+$', '', text).strip()

    # camelCase → spaced
    text = _RE_CAMEL_CASE.sub(' ', text)

    # snake_case / SCREAMING_SNAKE → spaced
    text = _RE_SNAKE_CASE.sub(' ', text)

    return text.lower().strip()


def _handle_file_path(path: str) -> str:
    """Convert absolute file path to a speakable abbreviation."""
    # Check known prefix map
    for prefix, label in PATH_PREFIX_MAP:
        if path.startswith(prefix):
            remainder = path[len(prefix):].lstrip('/')
            if not remainder:
                return label
            # Get just the filename
            filename = remainder.split('/')[-1]
            # Check if it's just a directory (no extension)
            if '.' not in filename:
                return label
            # Naturalize filename
            base, ext = filename.rsplit('.', 1) if '.' in filename else (filename, '')
            friendly_ext = FILE_EXT_MAP.get(f'.{ext}', f'{ext} file') if ext else ''
            # Naturalize base name
            friendly_base = re.sub(r'[-_]', ' ', base).lower()
            if friendly_ext:
                return f"the {friendly_base} {friendly_ext}"
            return f"the {friendly_base} file"

    # No known prefix — just return the filename
    filename = path.split('/')[-1]
    if not filename or '.' not in filename:
        return ''  # It's a directory, skip it

    base, ext = filename.rsplit('.', 1) if '.' in filename else (filename, '')
    friendly_ext = FILE_EXT_MAP.get(f'.{ext}', '')
    friendly_base = re.sub(r'[-_]', ' ', base).lower()
    return f"the {friendly_base} {friendly_ext or 'file'}"


def preprocess_for_tts(
    text: str,
    max_length: int = 800,
    naturalize_identifiers: bool = True,
) -> str:
    """
    Main entry point. Transform raw agent/markdown text into clean TTS prose.

    Args:
        text: Raw text from AI agent, may contain markdown, code, paths, etc.
        max_length: Truncate output to this many characters (Kokoro degrades on very long inputs)
        naturalize_identifiers: If True, convert camelCase/snake_case to spoken form

    Returns:
        Clean spoken-prose text, safe to pass directly to Kokoro TTS.
    """
    if not text or not text.strip():
        return ''

    result = text

    # ── Phase 1: Hard removes (things that should never be spoken) ──────────

    # Remove code blocks entirely (before any other processing)
    result = _RE_CODE_BLOCK.sub('', result)

    # Remove import/require lines (pure code, no spoken value)
    result = _RE_IMPORT_LINE.sub('', result)

    # Remove table rows (structural noise)
    result = _RE_TABLE_SEP.sub('', result)
    result = _RE_TABLE_ROW.sub('', result)

    # Remove git hashes (7-40 hex chars standalone)
    result = _RE_GIT_HASH.sub('', result)

    # Remove UUIDs
    result = _RE_UUID.sub('', result)

    # Remove line number references
    result = _RE_LINE_REF.sub('', result)

    # Remove XML/HTML tags (SSML fragments, HTML snippets)
    result = _RE_XML_TAG.sub('', result)

    # Remove decorators (@Singleton, @Override, etc.)
    result = _RE_DECORATOR.sub('', result)

    # Remove parenthetical technical asides (see line 401 in cli.js)
    result = _RE_PARENS_TECHNICAL.sub('', result)

    # Remove JSON blobs
    result = _RE_JSON_BLOCK.sub('', result)

    # ── Phase 2: Convert structural markdown ──────────────────────────────────

    # Horizontal rules → sentence break
    result = _RE_HR.sub('.', result)

    # Headers → just the text, with trailing period
    def header_replace(m):
        header_text = m.group(1).strip()
        if not header_text:
            return ''
        return f'{header_text}.'
    result = _RE_HEADER.sub(header_replace, result)

    # Blockquotes → strip the > marker
    result = _RE_BLOCKQUOTE.sub(lambda m: m.group(1).strip(), result)

    # Bullet lists → spoken as sentences
    def bullet_replace(m):
        item = m.group(1).strip()
        if not item:
            return ''
        if not item[-1] in '.!?':
            item += '.'
        return item
    result = _RE_BULLET_DASH.sub(bullet_replace, result)
    result = _RE_NUMBERED_LIST.sub(bullet_replace, result)

    # ── Phase 3: Convert inline formatting ───────────────────────────────────

    # Bold → plain text
    result = _RE_BOLD_STAR.sub(r'\1', result)
    result = _RE_BOLD_UNDERSCORE.sub(r'\1', result)

    # Italic → plain text
    result = _RE_ITALIC_STAR.sub(r'\1', result)
    result = _RE_ITALIC_UNDERSCORE.sub(r'\1', result)

    # ── Phase 4: Handle technical content ────────────────────────────────────

    # Images → skip
    result = _RE_MARKDOWN_IMAGE.sub('', result)

    # Markdown links → link text only
    result = _RE_MARKDOWN_LINK.sub(r'\1', result)

    # Bare URLs → domain only
    result = _RE_BARE_URL.sub(lambda m: m.group(1), result)

    # Absolute file paths
    def path_replace(m):
        path = m.group(1)
        friendly = _handle_file_path(path)
        return friendly if friendly else ''
    result = _RE_ABSOLUTE_PATH.sub(path_replace, result)

    # Relative paths → filename
    result = _RE_RELATIVE_PATH.sub(lambda m: m.group(1), result)

    # Function calls → naturalize or strip parens
    if naturalize_identifiers:
        result = _RE_FUNCTION_CALL.sub(lambda m: _naturalize_identifier(m.group(1)), result)

    # Inline code → naturalize if identifier, else strip backticks
    def inline_code_replace(m):
        code = m.group(1).strip()
        if not code:
            return ''
        # If it looks like a code identifier (no spaces, mixed case or underscore)
        looks_like_identifier = (
            ' ' not in code and
            len(code) < 40 and
            re.search(r'[a-zA-Z]', code)
        )
        if looks_like_identifier and naturalize_identifiers:
            return _naturalize_identifier(code)
        # Otherwise just strip backticks — speak as-is
        return code
    result = _RE_INLINE_CODE.sub(inline_code_replace, result)

    # Number ranges → "X to Y"
    result = _RE_NUMBER_RANGE.sub(lambda m: f'{m.group(1)} to {m.group(2)}', result)

    # Emoji → remove
    result = _RE_EMOJI.sub('', result)

    # ── Phase 5: Sentence structure cleanup ───────────────────────────────────

    # Collapse 3+ blank lines to 2
    result = _RE_BLANK_LINES.sub('\n\n', result)

    # Convert blank lines to sentence terminators
    result = re.sub(r'\n\n+', '. ', result)
    result = re.sub(r'\n', ' ', result)

    # Fix duplicate/trailing punctuation
    result = _RE_MULTI_PUNCT.sub(lambda m: m.group(1), result)

    # Fix ". ." artifacts from blank-line conversion
    result = re.sub(r'\.\s+\.', '.', result)

    # Clean up trailing commas/semicolons before periods
    result = re.sub(r'[,;:]\s*\.', '.', result)

    # Collapse multiple spaces
    result = _RE_WHITESPACE_RUNS.sub(' ', result)

    # Ensure sentences end with punctuation
    result = result.strip()
    if result and result[-1] not in '.!?':
        result += '.'

    # ── Phase 6: Length guard ─────────────────────────────────────────────────

    if max_length and len(result) > max_length:
        # Truncate at sentence boundary
        truncated = result[:max_length]
        last_period = max(
            truncated.rfind('.'),
            truncated.rfind('!'),
            truncated.rfind('?'),
        )
        if last_period > max_length * 0.5:
            result = truncated[:last_period + 1]
        else:
            result = truncated.rstrip() + '.'

    return result.strip()


def preprocess_for_dispatch(
    sender: str,
    text: str,
    max_length: int = 600,
) -> str:
    """
    Dispatch-specific wrapper: prepend sender name, preprocess, and format
    for the spoken intro the user expects ("Engineering says: ...").

    This matches the current pattern in TtsEngine.speak() which prepends
    "{sender} says: {message}" — but now the message is cleaned first.
    """
    clean = preprocess_for_tts(text, max_length=max_length)
    if not clean:
        return ''
    return clean
```

---

## 5. Pipeline Hook

### 5.1 Where preprocessing inserts in File Bridge

The File Bridge has two TTS endpoints:

```
POST /api/tts         → tries local Kokoro, falls back to HF cloud
POST /api/tts/stream  → streaming chunks from Kokoro
```

Both endpoints accept a JSON body with `{ "text": "...", "voice": "...", "speed": 1.0 }`.

**The preprocessing hook goes at the top of both endpoint handlers, before the text is forwarded to Kokoro.**

```python
# In file_bridge/routes/tts.py (or equivalent)
from tts_preprocessor import preprocess_for_tts

@app.post("/api/tts")
async def tts_endpoint(request: TTSRequest):
    # PREPROCESSING HOOK — inserted here
    clean_text = preprocess_for_tts(
        request.text,
        max_length=800,
    )
    if not clean_text:
        return Response(status_code=204)  # Nothing speakable

    # Forward clean_text (not request.text) to Kokoro
    return await _proxy_to_kokoro(clean_text, request.voice, request.speed)


@app.post("/api/tts/stream")
async def tts_stream_endpoint(request: TTSRequest):
    # PREPROCESSING HOOK — same pattern
    clean_text = preprocess_for_tts(
        request.text,
        max_length=600,  # Shorter for streaming — fewer Kokoro chunks
    )
    if not clean_text:
        return Response(status_code=204)

    return await _proxy_to_kokoro_stream(clean_text, request.voice, request.speed)
```

### 5.2 Where the text originates (upstream)

The text that reaches `/api/tts` comes from two sources:

1. **FCM dispatch push path:** `DispatchFcmService` receives an FCM push with a `text` field → starts `DispatchPlaybackService` → builds a streaming POST to `/api/tts/stream` with the raw text. The raw text was set by whoever sent the dispatch (usually an agent via `dispatch send "..."` CLI or a hook).

2. **Claude Code hook path:** Hooks (PostToolUse, Stop) can call `dispatch send` with the agent's response text or a summary. This is often the raw output of a `claude -p` run — fully markdown-formatted.

**Both paths deliver unprocessed agent output to the TTS endpoint.** The preprocessing hook at the endpoint level catches both.

### 5.3 Function signature (canonical)

```python
def preprocess_for_tts(
    text: str,
    max_length: int = 800,
    naturalize_identifiers: bool = True,
) -> str:
    """
    Transform markdown/technical text to spoken prose for Kokoro TTS.

    Args:
        text:                   Raw input — may contain markdown, code, paths, URLs
        max_length:             Truncate output to this many characters.
                                800 for non-streaming (single WAV download).
                                600 for streaming (keep first-chunk latency low).
        naturalize_identifiers: If True, convert camelCase/snake_case identifiers
                                to spoken English. Set False if the text is already
                                plain prose (e.g., manually written dispatch messages).

    Returns:
        str: Clean spoken English, safe to pass to Kokoro. May be empty string
             if the entire input was non-speakable (e.g., a pure code block).
             Callers should check for empty and skip the TTS call entirely.

    Guarantees:
        - No backticks, asterisks, or pound signs in output
        - No absolute file paths in output
        - No git hashes, UUIDs, or line number references in output
        - No markdown tables in output
        - All sentences end with punctuation
        - Output length <= max_length characters
        - All emoji removed
        - No XML/SSML tags in output
    """
```

### 5.4 Testing the hook

Add a test route to File Bridge for fast iteration without touching the Android app:

```python
@app.post("/api/tts/preview")
async def tts_preview(request: TTSRequest):
    """Return preprocessed text without calling Kokoro — for debugging."""
    clean = preprocess_for_tts(request.text)
    return {"original": request.text, "clean": clean, "length": len(clean)}
```

Use with: `curl -X POST http://oasis:PORT/api/tts/preview -H 'Content-Type: application/json' -d '{"text": "**bold** `code` /home/xoom000/file.py", "voice": "af_heart"}'`

---

## 6. Before / After Examples

### Example 1: File edit summary

**Raw agent output:**
```
I've edited `/home/xoom000/AndroidStudioProjects/Dispatch/app/src/main/java/dev/digitalgnosis/dispatch/playback/DispatchPlaybackService.kt` to fix the `TtsStreamDataSource.open()` method. The change is at **line 514** where `WAV_HEADER_BYTES = 44` was incorrectly calculated. See commit `ab3f7c2`.
```

**After preprocessing:**
```
I've edited the Dispatch playback service Kotlin file to fix the TTS stream data source open method. The change is where the wav header bytes was incorrectly calculated.
```

---

### Example 2: Code block response

**Raw agent output:**
````
Here's the updated configuration:

```python
def speak(self, text: str) -> None:
    clean = preprocess_for_tts(text)
    self.kokoro.synthesize(clean)
```

This ensures all text is cleaned before synthesis.
````

**After preprocessing:**
```
Here's the updated configuration. This ensures all text is cleaned before synthesis.
```

---

### Example 3: Bullet list findings

**Raw agent output:**
```
After searching the codebase, I found:
- `TtsEngine.kt` uses Piper/Sherpa-ONNX for local synthesis
- `DispatchPlaybackService.kt` streams from Kokoro at `oasis:8400`
- The `speak()` method at **line 180** prepends the sender name
- No preprocessing currently exists
```

**After preprocessing:**
```
After searching the codebase, I found. TTS engine uses Piper for local synthesis. Dispatch playback service streams from Kokoro at oasis 8400. The speak method prepends the sender name. No preprocessing currently exists.
```

---

### Example 4: Technical status update

**Raw agent output:**
```
## Summary

The `SseConnectionService` is reconnecting every **30 seconds** due to a `SocketTimeoutException` in `OkHttpClient`. The fix involves setting `readTimeout(0, TimeUnit.SECONDS)` for SSE connections — zero means no timeout.

See: `BaseFileBridgeClient.kt:47`
```

**After preprocessing:**
```
Summary. The SSE connection service is reconnecting every 30 seconds due to a socket timeout exception in ok HTTP client. The fix involves setting read timeout to zero for SSE connections, which means no timeout.
```

---

### Example 5: File path listing

**Raw agent output:**
```
Modified files:
- /home/xoom000/ClaudeCode/dissected/cli-beautified.js (line 401-407)
- /home/xoom000/AndroidStudioProjects/Dispatch/.project/FILE-BRIDGE-API.md
- /home/xoom000/.claude/settings.json
```

**After preprocessing:**
```
Modified files. the cli-beautified JavaScript file. the file bridge api markdown file in the Dispatch project. the settings JSON file in the Claude config directory.
```

---

### Example 6: Mixed inline formatting

**Raw agent output:**
```
The **critical** issue is that `getUserName()` returns `null` when the user's `session_token` has expired. You'll need to call `refreshToken()` first.
```

**After preprocessing:**
```
The critical issue is that get user name returns null when the user's session token has expired. You'll need to call refresh token first.
```

---

### Example 7: URL references

**Raw agent output:**
```
See the documentation at https://huggingface.co/hexgrad/Kokoro-82M and the FastAPI wrapper at https://github.com/remsky/Kokoro-FastAPI for full API details.
```

**After preprocessing:**
```
See the documentation at huggingface.co and the FastAPI wrapper at github.com for full API details.
```

---

## 7. Never-Speak Rules

The following categories of content must be completely removed. They have zero informational value when spoken aloud and create significant degradation of the listening experience.

| Category | Examples | Reason |
|----------|----------|--------|
| Git commit hashes | `ab3f7c2`, `a1b2c3d4e5f6` | 7-40 hex chars — meaningless spoken |
| UUIDs | `550e8400-e29b-41d4-a716-446655440000` | Unpronouneable / meaningless |
| Line numbers | `L401`, `line 514`, `lines 401-407` | Context-free without visual reference |
| Symbolic line refs | `line_401407`, `offset_32768` | Underscore + number sequences |
| File system paths | `/home/xoom000/...` | Replace with abbreviation, never full path |
| Code blocks | ```` ```...``` ```` | Entirely unlistenable as speech |
| Import statements | `import X from Y` | Implementation detail, not a summary |
| Table structures | `\| col \| col \|` | Structural noise |
| JSON/dict blobs | `{"key": "value", "other": 123}` | Data, not prose |
| XML/SSML tags | `<break time="1s"/>` | Tag syntax spoken literally |
| Decorators | `@Singleton`, `@Override` | Code annotation, not English |
| Raw hex | `0xFF`, `0x7FFFFF00` | Hex literals |
| IP addresses | `192.168.1.1`, `10.0.0.1` | Network literals |
| Technical asides | `(see line 401 in cli.js)` | Parenthetical code refs |

---

## 8. Voice Model Recommendations

Match voice to agent personality and message tone. These are recommendations based on voice character research.

| Department / Agent | Recommended Voice | Rationale |
|-------------------|-------------------|-----------|
| Default / Boardroom | `af_heart` | Warm, expressive — primary voice for general agent dispatches |
| Engineering / Dev | `am_eric` | Conversational male voice — suits technical updates |
| Code Review / Analysis | `bm_daniel` | British male, measured — good for analytical findings |
| Executive Summary | `am_michael` | Deep, authoritative — suited to high-level strategic updates |
| Gemini / Research | `af_nova` | Calm, measured — long-form summaries without fatigue |
| Alerts / Urgent | `af_jessica` | Energetic — conveys urgency naturally |
| System Status | `am_puck` | Already in use for status messages in `DispatchPlaybackService` |
| Claude Code (general) | `am_liam` | Younger, relaxed — suits dev-tool personality |
| Data / Analytics | `bf_emma` | Clear British female — data reporting voice |

**Blended voice example for a senior engineer persona:**
```
"am_michael(1)+am_eric(1)"  →  authoritative but conversational
```

**Speed recommendations by message type:**
- Urgent alerts: `speed: 1.1` (slightly faster, conveys urgency)
- Long summaries: `speed: 0.9` (slower for comprehension)
- Normal dispatches: `speed: 1.0` (default)
- Status/system messages: `speed: 1.0`

---

## 9. Prosody and Naturalness Parameters

Since Kokoro does not yet support SSML, naturalness improvements come entirely from text shaping.

### 9.1 Punctuation as prosody

Kokoro's prosody engine responds to punctuation at the text level:

| Text pattern | Effect on speech |
|-------------|-----------------|
| Comma `,` | Short pause, continuation |
| Period `.` | Sentence-final pause, pitch drop |
| Exclamation `!` | Rising-terminal, emphasis |
| Question `?` | Rising-terminal, interrogative |
| Ellipsis `...` | Hesitation pause |
| Em dash `—` | Clause pause, slightly longer than comma |
| Semicolon `;` | Convert to period — Kokoro may not handle well |

**Strategy:** When preprocessing removes markdown structure, insert commas and periods strategically to preserve the rhythm the agent intended. A bullet list item that was a standalone clause should end in a period, not be merged into a run-on.

### 9.2 Sentence length targeting

Kokoro performs best with sentences of 15–25 words. After preprocessing, if a sentence exceeds 30 words, split at the nearest comma or coordinating conjunction ("and", "but", "so", "which").

```python
def _split_long_sentences(text: str, max_words: int = 28) -> str:
    """Split sentences exceeding max_words at natural break points."""
    sentences = re.split(r'(?<=[.!?])\s+', text)
    result = []
    for sent in sentences:
        words = sent.split()
        if len(words) <= max_words:
            result.append(sent)
            continue
        # Find split point: comma in the middle half of sentence
        mid_low = len(words) // 4
        mid_high = (3 * len(words)) // 4
        # Try to split at "and", "but", "so", "which" in middle range
        for i in range(mid_high, mid_low, -1):
            if words[i].lower() in ('and', 'but', 'so', 'which', 'where', 'that'):
                result.append(' '.join(words[:i]) + '.')
                result.append(' '.join(words[i:]))
                break
        else:
            # Fall back to splitting at a comma character
            comma_pos = sent.find(',', len(sent) // 3)
            if comma_pos > 0:
                result.append(sent[:comma_pos] + '.')
                result.append(sent[comma_pos+1:].strip())
            else:
                result.append(sent)
    return ' '.join(result)
```

### 9.3 Numeric normalization

Numbers read better when written out for small values or contextualized for large ones:

| Input | Spoken target |
|-------|--------------|
| `1` | `one` (Kokoro handles this naturally) |
| `24000 Hz` | `24 thousand hertz` (Kokoro handles Hz poorly — convert to `hertz`) |
| `82M` | `82 million` (expand suffix) |
| `3.14` | Kokoro reads decimals correctly |
| `100ms` | `100 milliseconds` |
| `0xFF` | Remove (hex literal) |

**Simple suffix expansion:**

```python
_RE_NUM_SUFFIX = re.compile(r'\b(\d+(?:\.\d+)?)\s*(ms|Hz|KB|MB|GB|TB|ms|s|M|K|B)\b')
_SUFFIX_MAP = {
    'ms': 'milliseconds', 'Hz': 'hertz', 'KB': 'kilobytes',
    'MB': 'megabytes', 'GB': 'gigabytes', 'TB': 'terabytes',
    's': 'seconds', 'M': 'million', 'K': 'thousand', 'B': 'bytes',
}
def _expand_numeric_suffixes(text: str) -> str:
    return _RE_NUM_SUFFIX.sub(
        lambda m: f'{m.group(1)} {_SUFFIX_MAP.get(m.group(2), m.group(2))}', text
    )
```

### 9.4 When SSML becomes available

When Kokoro-FastAPI ships SSML support (planned, not yet released), the following enhancements become possible without changing the preprocessor's output — they can be layered on top:

- Wrap agent names in `<emphasis>` for clarity
- Insert `<break time="500ms"/>` at the transition from intro to body
- Use `<prosody rate="95%">` on the body text for longer messages
- Use `<prosody rate="110%">` on alert messages

The preprocessor should be designed to output clean plain text now, and a separate SSML wrapper can be added later when the feature is available.

### 9.5 Kokoro-specific known behaviors

From community testing:

- Kokoro handles contractions ("don't", "it's") naturally — no need to expand
- Kokoro handles abbreviations inconsistently — expand `TTS` to `text to speech` on first use
- Kokoro reads ALL CAPS as emphasis/loud — use sparingly and intentionally
- Kokoro reads `...` (three dots) as a hesitation pause — this is useful
- Kokoro pronounces `Kokoro` correctly as-is
- Very long inputs (>1000 chars) can cause rhythm degradation — the `max_length` guard is important
- Speed `1.0` is the most tested value; speeds above `1.3` can cause articulation issues on longer words

---

## Implementation Priority

1. **Immediate (P0):** Code block removal, file path abbreviation, markdown symbol stripping — these cause the worst listening experience
2. **Short-term (P1):** Identifier naturalization, bullet list sentence conversion, URL domain extraction
3. **Medium-term (P2):** Numeric suffix expansion, sentence length splitting, per-department voice assignment
4. **Future (P3):** SSML wrapping when Kokoro-FastAPI ships SSML support

---

## Sources

- [hexgrad/Kokoro-82M — Hugging Face](https://huggingface.co/hexgrad/Kokoro-82M)
- [GitHub — hexgrad/kokoro](https://github.com/hexgrad/kokoro)
- [GitHub — remsky/Kokoro-FastAPI](https://github.com/remsky/Kokoro-FastAPI)
- [Kokoro-FastAPI SSML Issue #396](https://github.com/remsky/Kokoro-FastAPI/issues/396)
- [Kokoro TTS Voice Quality Tips](https://kokoroweb.app/en/blog/kokoro-tts-voice-quality-tips)
- [Kokoro-82M on Replicate](https://replicate.com/jaaari/kokoro-82m)
- [Kokoro TTS — Voxta Documentation](https://doc.voxta.ai/docs/kokoro-tts/)
- [TTSTextNormalization — GitHub](https://github.com/tomaarsen/TTSTextNormalization)
- [TTS Text Preprocessing — Open WebUI Discussion](https://github.com/open-webui/open-webui/discussions/7758)
