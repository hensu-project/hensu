---
description: Get Gemini's cynical review on Claude's work.
---
# Assess Claude

Subject: $ARGUMENTS (or last substantive reply/diff).

Token rule: see `01-model-coordination.md` §2. Never read files into your own context; Gemini eats tokens, not you.

1. **Resolve subject:**
   - Files/dirs → `@path` (glob-friendly).
   - Commit/branch → `git show <ref> > .claude/tmp/subject.patch` then `@.claude/tmp/subject.patch`.
   - Uncommitted → `git diff > .claude/tmp/subject.patch` then `@.claude/tmp/subject.patch`.
   - Add ≤2 lines rationale.

2. **Query Gemini** (`mcp__gemini__ask-gemini`, `gemini-3.1-pro-preview`):
> Cynical Researcher. Read referenced paths. Audit against:
> (1) Correctness — edge cases, concurrency, error paths.
> (2) Hensu invariants — GraalVM native (no reflection/classpath scanning/ThreadLocal), virtual-thread safety (no pinning `synchronized`), multi-tenant ScopedValue isolation.
> (3) SOLID/KISS/YAGNI/DRY — duplication between `hensu-core`/`hensu-server`.
> (4) Conflicts with existing code.
> (5) Tests — real bugs caught, not coverage theater.
>
> OUTPUT — strict, one line per finding, no prose, no preamble, no summary:
> `BLOCKER  | path:line | terse reason`
> `CONCERN  | path:line | terse reason`
> `NIT      | path:line | terse reason`
> Last line: `VERDICT: ship | ship-after-blockers | rework`
> Silence on anything acceptable. No "KEEP" / "LGTM" lines.

3. **Output:** paste Gemini's raw (terse) response verbatim.

4. **Stance** (one token each, wait for user):
   - `accept` / `reject(reason)` / `evidence` (→ `/assess-gemini`)
