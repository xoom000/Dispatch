# DG Messages — Visual Identity Concept

**Date:** 2026-03-19
**Author:** Cipher
**Directive:** Google Messages structural patterns, DG visual identity. Not a clone.

---

## The Structural Pattern (Stolen from Google)

These are the patterns we adopt exactly — they're proven, tested, and users already understand them:

- **Unified layout**: messages + input bar + keyboard are ONE connected surface. No gaps, no shadows between them. When keyboard opens, the whole thing moves as a unit.
- **ConstraintLayout keyboard behavior**: message list constrained above compose bar. `adjustResize` shrinks the window, compose bar rises with keyboard, message list fills remaining space.
- **Bubble grouping**: consecutive messages from same sender cluster tight (2dp gap). New sender run gets 8dp gap. Only last bubble in a run shows timestamp.
- **Asymmetric corner radii**: 20dp on most corners, 4dp on the "tail" side. Outgoing tail is bottom-right. Incoming tail is bottom-left. Middle bubbles in a group get 4dp on both tail-side corners.
- **Input pill**: rounded text field (24dp radius) with action icons inside/outside the pill. Send button bottom-aligns as text grows multi-line.
- **Collapsing header**: conversation header shrinks on scroll, snaps to collapsed/expanded.

---

## The DG Identity (Where We Diverge)

### Color Story: "Midnight Operations Center"

Google Messages uses teal outgoing and gray incoming. We don't.

DG is an AI operations platform. Nigel runs a fleet of agents. The color story should feel like a command center at night — dark surfaces, accent glows, clear visual hierarchy between human and machine.

**Concept A — "Deep Blue Command"**
- Background: true black (#0A0A0C) — darker than Google's #131314
- Outgoing (Nigel): deep electric blue (#0D47A1 → #1565C0 gradient) with cool white text
- Incoming (Agent): charcoal glass (#1A1D23) with soft blue-white text (#B8C6DB)
- Incoming (Dispatch): midnight navy (#0A1A3A) with cyan accent text (#80BFFF) — already in Color.kt
- Input pill: near-invisible border (#2A2D35), transparent fill, subtle glow on focus
- Accent: electric blue pulse on send, cyan typing indicators

**Concept B — "Neon Terminal"**
- Background: deep space (#050508)
- Outgoing (Nigel): emerald dark (#0D3B26) with bright green text (#4CAF50)
- Incoming (Agent): dark slate (#141820) with warm gray text (#D0D0D0)
- Incoming (Dispatch): same midnight navy, but dispatch messages get a subtle left-border accent line (2dp, cyan)
- Input pill: dark with a 1dp phosphor-green border that brightens on focus
- Accent: green pulse animations, terminal-inspired typing cursor blink

**Concept C — "Glass Morphic Ops"**
- Background: ultra dark with subtle noise texture (#0C0C10)
- Outgoing (Nigel): frosted blue glass with blur effect (#1A3A6B at 80% opacity, backdrop blur)
- Incoming (Agent): frosted gray glass (#1E2028 at 90% opacity)
- Input pill: glass effect — semi-transparent with visible blur through to the message list
- Accent: soft white glows, parallax-aware reflections
- Note: glassmorphism is already the DG website aesthetic

### Animations: "Not Google, Not iOS"

Google Messages has minimal animation. iOS Messages has the bubble pop. We do something different.

**Message arrival:**
- New bubble slides up from bottom with a subtle scale (0.95 → 1.0) and fade (0→1). Duration: 200ms, easeOutQuint.
- NOT the Google "just appear" pattern. NOT the iOS "pop in from below." A smooth, confident entrance.

**Send animation:**
- Outgoing bubble does a quick slide-right-and-settle (translate X: -20dp → 0dp, 150ms). Feels like the message is launching toward the recipient.

**Typing indicator:**
- Three dots with a wave animation (sequential vertical bounce with 120ms stagger between dots)
- Color: DG accent (blue or green depending on concept)
- NOT Google's gray dots. Use the accent color. It should feel alive.

**Keyboard transition:**
- Use `WindowInsetsAnimationCompat.Callback` so the layout smoothly animates with the keyboard, not after it.
- Message list scrolls down during keyboard rise (content stays anchored at visual bottom).

**JumpToBottom:**
- Current implementation is fine but should use a spring animation (slight overshoot) instead of linear slide.

### Typography

Google uses Google Sans. We can't (proprietary). Options:

- **Inter** — clean, modern, excellent at small sizes. The safe choice.
- **JetBrains Mono** for code/tool bubbles — monospace that reads well
- Keep M3 default (Roboto) for now, add custom font as a polish pass

### Sender Differentiation

Google has two sender types (you / them). We have four (Nigel, Agent, Dispatch, Tool). This is a strength — lean into it:

- **Nigel bubbles**: right-aligned, accent color, confident corners
- **Agent bubbles**: left-aligned, neutral surface, with a subtle department-color left border (2dp) so you can tell which agent sent it at a glance
- **Dispatch bubbles**: left-aligned, midnight navy, with a speaker icon or waveform mini-visualization indicating it was spoken aloud
- **Tool bubbles**: collapsed by default (just showing the tool name + status), tap to expand. Monospace text. Muted colors. They're infrastructure, not conversation.

---

## Image Generation Prompts

Use these with Midjourney, DALL-E, Ideogram, or any image generator to visualize the concepts.

### Prompt 1 — Deep Blue Command (Conversation View)

```
Dark Android messaging app conversation screen, Material Design 3, AMOLED black background (#0A0A0C). Right-aligned outgoing message bubbles in deep electric blue (#0D47A1) with white text, left-aligned incoming bubbles in dark charcoal (#1A1D23) with soft gray-blue text. Rounded bubble corners (20dp large, 4dp small on tail side). Minimal input bar at bottom with pill-shaped text field, transparent background, thin border, send button. No elevation shadows. Clean, professional, command center aesthetic. Dark mode only. Pixel 9 phone mockup, 1080x2400 resolution. UI design, Figma style.
```

### Prompt 2 — Deep Blue Command (With Keyboard)

```
Dark Android messaging app with keyboard open, AMOLED black background. Message bubbles and input bar form one continuous surface with no gaps or shadows. Input pill sits directly above the dark keyboard. Right-aligned blue outgoing bubbles, left-aligned dark gray incoming bubbles. The keyboard, input bar, and messages are visually connected as one cohesive unit. Material Design 3, clean lines, no unnecessary padding. Pixel 9 phone mockup, 1080x2400 resolution. Professional dark UI design.
```

### Prompt 3 — Neon Terminal (Conversation View)

```
Dark Android AI operations messaging app, cyberpunk-inspired but professional. Deep space black background (#050508). Right-aligned outgoing bubbles in dark emerald (#0D3B26) with green text (#4CAF50). Left-aligned incoming AI agent bubbles in dark slate (#141820) with warm gray text and a thin cyan accent border on the left edge. Input bar at bottom with pill-shaped text field, phosphor-green border glow on focus. Three-dot typing indicator in accent green with wave animation. Terminal aesthetic meets modern mobile design. Pixel 9 phone mockup, 1080x2400. UI/UX dark theme design.
```

### Prompt 4 — Glass Morphic Ops (Conversation View)

```
Dark Android messaging app with glassmorphism aesthetic. Ultra-dark background (#0C0C10) with subtle noise texture. Right-aligned outgoing message bubbles in frosted blue glass effect (semi-transparent blue, backdrop blur visible). Left-aligned incoming bubbles in frosted dark gray glass. Input bar with glass-effect pill — semi-transparent with blur showing messages behind. Soft white glow accents. Rounded corners (20dp). Clean, futuristic, AI operations center feel. No harsh shadows, all surfaces feel layered and translucent. Pixel 9 phone mockup. Premium dark UI design.
```

### Prompt 5 — Multi-Sender Types (Feature Showcase)

```
Dark Android AI agent messaging app showing four distinct message types in one conversation: (1) right-aligned human messages in deep blue, (2) left-aligned AI agent responses in dark charcoal with a colored left border stripe, (3) left-aligned voice dispatch messages in midnight navy with a small waveform icon, (4) collapsed tool/function call cards in muted dark gray with monospace text. Each sender type is visually distinct but harmonious. AMOLED black background, Material Design 3, rounded bubble corners. Professional command center aesthetic. Pixel 9 mockup, 1080x2400. UI design concept.
```

### Prompt 6 — Conversation List (Home Screen)

```
Dark Android messaging app conversation list screen. AMOLED black background. Each row shows: colored circle avatar with initials on the left, department name in bold white, last message preview in muted gray below, smart timestamp on the right. Unread conversations have a small blue badge and bold text. Bottom navigation bar with 4 tabs. No list dividers. Clean spacing (80dp row height). Material Design 3. Dark professional aesthetic, AI operations platform feel. Pixel 9 phone mockup, 1080x2400. UI design.
```

---

## Next Steps

1. Nigel picks a concept direction (or mixes elements from multiple)
2. Generate images for visual validation
3. Build the structural changes (unified layout, keyboard behavior)
4. Apply the chosen visual identity
5. Add animations last (they're polish, not structure)
