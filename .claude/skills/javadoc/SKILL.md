---
name: javadoc
description: Use when writing or editing Javadoc/KDoc on public APIs in hensu-core, hensu-dsl, hensu-serialization, or hensu-server. Covers Markdown Javadoc (`///`) syntax for Java 25, required tag order, `{@link}` usage, `@since` policy, sealed-hierarchy documentation, and the "documentation as structured metadata" philosophy. Triggers on phrases like "add javadoc", "document this API", "write kdoc", or any edit that adds/modifies `///`, `/** */`, or `/** */` doc comments on public types, methods, or fields.
---

# Javadoc Authoring Skill

Authoritative source: [`docs/javadoc-guide.md`](../../../docs/javadoc-guide.md).

## Workflow

1. **Read the full guide** — `docs/javadoc-guide.md` contains the tag tables, ordering rules, and module-specific conventions. Do not attempt to author Javadoc from memory; the guide is the single source of truth.
2. **Identify the surface** — public class, interface, method, field, or package. Non-public members do not require Javadoc unless they are part of an SPI.
3. **Use Markdown Javadoc (`///`)** for all new Java 23+ code. Use KDoc (`/** */`) for Kotlin.
4. **Apply the required tag set** for the surface type (see §3 of the guide).
5. **Verify with `./gradlew javadoc`** before declaring done on documentation-only changes.

## Hard rules (do not violate)

- If a machine cannot parse the doc block, it is incomplete.
- Every public method documents `@param`, `@return`, and `@throws` where applicable — no exceptions.
- Sealed hierarchies list all permitted subtypes in the parent Javadoc.
- No legacy HTML (`<p>`, `<ul>`, `<b>`) in new `///` blocks — use Markdown equivalents.

## When in doubt

Re-read the relevant section of `docs/javadoc-guide.md` rather than guessing. The guide's tag tables are exhaustive.
