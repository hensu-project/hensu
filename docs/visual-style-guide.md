# Visual Style Guide

Standards for all diagrams, charts, and badges across Hensu documentation.

---

## Part 1 – ASCII Linedraw (Javadoc / KDoc)

Standards for ASCII architectural diagrams in Javadoc and KDoc comments. Markdown files
should use Mermaid (Part 2) instead.

### Character Palette
- **Corners/Junctions:** Use `+` exclusively.
- **Horizontal Lines:** Use typographic Em Dash `—` (U+2014).
- **Vertical Walls:** Use Box-Drawing Pipe `│` (U+2502).
- **Flow:** Use `————>` for horizontal and `V`/`^` for vertical.

### Prohibited Characters
- Do NOT use rounded Unicode corners: `┌`, `└`, `╭`, `╰`.
- Do NOT use emojis within box structures (they break fixed-width alignment).
- Do NOT use standard hyphens `-` or standard pipes `|` for structural boundaries.

### Fencing
- **Standard:** Use triple backticks ` ``` ` for all diagrams.
- **Context:** This applies to `///` style Markdown Javadoc in `.java` files and `/** */` KDoc in `.kt` files.

---

## Part 2 – Mermaid Diagrams (Markdown)

Standards for Mermaid diagrams in `.md` files. Ensures visual consistency across README, docs,
and any rendered Mermaid blocks on GitHub.

### Design Philosophy

Dark mode, minimal: **monochromatic grays, one accent color, thin strokes, restraint**.

- Structure recedes; only flow lines and key artifacts carry the accent.
- Hierarchy comes from subtle fill differences, not border color variety.
- Labels are short and clean – let the layout communicate, not the text.

### Color Palette

| Token             | Hex       | Usage                                      |
|-------------------|-----------|--------------------------------------------|
| `background`      | `#1c1c1e` | Outermost wrapper / invisible subgraph     |
| `surface`         | `#2c2c2e` | Subgraph fills, node fills                 |
| `surface-nested`  | `#3a3a3c` | Nested subgraph fills (one level deeper)   |
| `border`          | `#3a3a3c` | Subgraph strokes                           |
| `border-subtle`   | `#48484a` | Node strokes, nested subgraph strokes      |
| `text-primary`    | `#ebebf5` | All node and subgraph label text           |
| `text-secondary`  | `#8e8e93` | Muted / artifact labels                    |
| `accent`          | `#0A84FF` | Flow lines, key artifact borders (one use) |
| `brand`           | `#5E5CE6` | Logo, hero callouts                        |
| `warning`         | `#FF9F0A` | Status badges (e.g. Pre-Beta)              |

### Stroke Rules

- **All strokes: 1px.** No heavy borders. Precision over weight.
- **Subgraphs:** `stroke:<border>` (`#3a3a3c`)
- **Nodes:** `stroke:<border-subtle>` (`#48484a`)
- **Flow lines:** `stroke:<accent>` (`#0A84FF`), 1px

### Node Shapes

- **Prefer stadium (pill) shapes:** `(["Label"])` – rounded, clean.
- **Avoid rectangles** `["Label"]` unless representing data/artifacts.

### Layout

- Use `flowchart LR` (left-to-right) for pipeline/flow diagrams.
- Use `flowchart TD` (top-down) for hierarchy/dependency diagrams.
- Keep labels concise — max 2-3 words per node.
- Omit step numbers from link labels unless ordering is ambiguous.

### Style Declaration Format

- **No spaces after colons:** `fill:#2c2c2e`, not `fill: #2c2c2e`.
- **Comma-space separators:** `fill:#2c2c2e, stroke:#48484a, color:#ebebf5`.
- **Align style blocks** with consistent whitespace for readability.
- **Group styles:** subgraphs first, then nodes, then `linkStyle default` last.

### Example

```
style bg     fill:#1c1c1e, stroke:none
style zone   fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px
style nested fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px
style node   fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px
style key    fill:#2c2c2e, stroke:#0A84FF, color:#ebebf5, stroke-width:1px

linkStyle default stroke:#0A84FF, stroke-width:1px
```

### Prohibited

- Do NOT use more than one accent color per diagram.
- Do NOT use saturated primaries (pure red, green, blue) for borders.
- Do NOT use `stroke-width` greater than `1px` on any element.
- Do NOT use `stroke-dasharray` except for internal/nested subgraphs.
- Do NOT use `filter`, `url()`, or other CSS functions – Mermaid's parser rejects them.

---

## Part 3 – Badges (shields.io)

Standards for shields.io badges in Markdown headers.

| Property     | Value         | Rationale                                       |
|--------------|---------------|-------------------------------------------------|
| `style`      | `flat-square` | Clean, no rounded distractions                  |
| `labelColor` | `#21262d`     | GitHub dark-mode card color – visible but quiet |
| Value color  | `#636366`     | Tertiary gray – metadata recedes                |
| Status badge | `#FF9F0A`     | Warning/status accent – max one badge per row   |

- CI/status badges keep dynamic colors (pass/fail signal).
- Static badges use the gray system; only **one** badge per row gets the accent.
