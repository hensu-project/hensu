---
description: Check and fix stale documentation after a code change.
---
# Doc Staleness Check

Target: $ARGUMENTS (branch name, PR description, or "current diff").

Walk documentation in dependency order. Each step is a **separate user interaction** — report
staleness findings, then wait for "fix" or "skip" before proceeding.

## Step order

1. **Core module** — `hensu-core/README.md` + `docs/developer-guide-core.md`
2. **DSL** — `hensu-dsl/README.md` + `docs/dsl-reference.md`
3. **Server** — `hensu-server/README.md` + `docs/developer-guide-server.md`
4. **Serialization** — `hensu-serialization/README.md` + `docs/developer-guide-serialization.md`
5. **CLI** — `hensu-cli/README.md`
6. **Architecture** — `docs/unified-architecture.md`
7. **Cross-cutting** — `AGENTS.md`
8. **Root** — `README.md`

## Per-step procedure

1. Read the target doc(s) for the current step.
2. Compare against the change set (diff, new files, modified interfaces).
3. Report findings as a numbered list: line range, what is stale, why.
4. If nothing is stale, say "Not stale" and move to the next step automatically.
5. If stale, wait for user to say "fix" or "skip".
6. After fix/skip, move to the next step.

## Rules

- Never batch multiple steps — one step per turn.
- "Not stale" steps auto-advance; stale steps pause.
- When fixing, prefer minimal edits that match existing prose style.
- Do not add content beyond what the change set requires.
- Follow `02-output-density.md` for chat replies.
