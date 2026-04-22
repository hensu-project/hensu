---
name: visual-style
description: Use when producing or editing diagrams, charts, badges, or ASCII architecture art anywhere in the Hensu repo — Mermaid blocks in `.md` files, ASCII line-drawings in `///` Javadoc or `/** */` KDoc, README badges, or any rendered visual. Enforces the monochrome dark-mode Mermaid palette, the ASCII character palette (`+`, `—`, `│`, no rounded corners, no emojis in boxes), and badge color conventions. Triggers on phrases like "add a diagram", "draw the architecture", "update the badge", "mermaid", "ascii diagram", or any edit that adds/modifies ```` ```mermaid ```` fences, ASCII box art, or shields.io badge URLs.
---

# Visual Style Skill

Authoritative source: [`docs/visual-style-guide.md`](../../../docs/visual-style-guide.md).

## Workflow

1. **Read the full guide** — `docs/visual-style-guide.md` has the exact character palette, Mermaid theme variables, and badge color tokens. Do not author visuals from memory.
2. **Pick the right medium:**
    - **`.java` / `.kt` files** → ASCII line-draw (Part 1 of the guide)
    - **`.md` files** → Mermaid (Part 2)
    - **Badges** → shields.io with the project palette (Part 3)
3. **Apply the palette verbatim.** Copy theme variables and color tokens from the guide — do not approximate.
4. **Verify rendering** — preview Mermaid blocks on GitHub or a Mermaid live editor before committing.

## Hard rules

- **ASCII:** `+` for corners, `—` (em dash U+2014) for horizontals, `│` (U+2502) for verticals. **Prohibited:** `┌`, `└`, `╭`, `╰`, plain `-`, plain `|`, emojis inside boxes.
- **Mermaid:** dark mode, monochrome grays, one accent color, thin strokes. No rainbow palettes.
- **Badges:** shields.io only, project palette only, stable left-label wording.

## When in doubt

Re-read the relevant Part of `docs/visual-style-guide.md`. The character palette and color tokens are not negotiable — consistency across the repo is the whole point.
