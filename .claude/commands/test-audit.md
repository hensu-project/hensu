---
description: Audit tests via Gemini (DROP/WEAK/ADD).
---
# Test Audit

Target: $ARGUMENTS (or ask user which tests/paths/diff).

Token rule: see `01-model-coordination.md` §2. Never inline test code or diffs.

1. **Resolve target:**
   - Files/dirs → `@path` (glob-friendly).
   - Uncommitted tests → `git diff -- '*Test*' '*test*' > .claude/tmp/tests.patch` then `@.claude/tmp/tests.patch`.

2. **Query Gemini** (`mcp__gemini__ask-gemini`, `gemini-3.1-pro-preview`):
> Cynical Researcher. Read referenced test paths. Question: "Can this catch a real prod bug?"
> Repo has ~1000 tests; net delta must be ≤ 0. Prefer DROP.
> Classify:
> - DROP — tautological, trivial, framework/language, duplicate, or asserts only what the type system guarantees.
> - WEAK — real target, poor execution (over-mocked, asserts impl detail, brittle).
> - ADD — uncovered prod failure mode. Be stingy.
>
> OUTPUT — strict, one line per finding, no prose, no preamble:
> `DROP | path:line | test name | bug it misses`
> `WEAK | path:line | test name | concrete rewrite`
> `ADD  | path:— | area          | prod failure mode`
> Last line: `NET: -X +Y = {sign}Z`
> SILENCE ON KEEP. Do not emit anything for acceptable tests.

3. **Output:** paste Gemini's raw (terse) response verbatim.

4. **Recommend** top 3 to action. Wait for user.
