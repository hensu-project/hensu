# Contributing to Hensu

First off, thank you for showing interest in Hensu! We are building an industrial-grade agentic infrastructure for the
JVM, and we value precision, performance, and type-safety.

Hensu is currently in **Alpha (70% Feature-Complete)**. We are focusing on stabilizing the core logic and ensuring
seamless GraalVM native-image execution.

---

## Technical Foundation

- **Language:** Java 25 / Kotlin
- **Framework:** Quarkus
- **Build Tool:** Gradle (with Toolchain support)
- **Target:** Native binaries via GraalVM

---

## How to Contribute

### 1. Repository Intelligence (Pommel)

Hensu uses **Pommel** for semantic context retrieval. If you are using an AI coding agent (Claude, Cursor, etc.), ensure
it utilizes the project's semantic rulesets to scan for relevant logic. This ensures the agent understands the
architecture before suggesting changes.

### 2. The Pull Request Process

To maintain our 70% (and climbing) verification rate, we follow a strict PR process:

- **Mandatory Approval:** All PRs require an approving review from the Code Owner (@alxsuv).
- **Linear History:** We use **Squash Merges** only. Please keep your branch history clean.
- **Native Verification:** If your change affects the `core` or `server`, you must verify that the project still
  compiles to a GraalVM native image.

### 3. Development Standards

- **Conventional Commits:** Use `feat:`, `fix:`, `docs:`, `refactor:`, or `chore:`.
- **Documentation:** If you add a feature to the `dsl` or `core`, the corresponding documentation in `/docs` must be
  updated in the same PR.
- **No Reflection:** Avoid using Java reflection unless absolutely necessary for Quarkus/GraalVM compatibility.

---

## Testing & Verification

We are moving toward 100% verification. Every PR should include:

- Unit tests for new logic.
- Integration tests for DSL-to-Engine transitions.
- A confirmation that `gradle nativeCompile` succeeds.

---

## Reporting Issues

If you find a bug or a performance bottleneck, please open an issue with the `type: bug` or `area: performance` label.
Provide a minimal reproducible example, preferably as a small DSL snippet.

---

**Lead Developer:** [@alxsuv](https://github.com/alxsuv)  
*“Building the infrastructure for the next generation of JVM agents.”*
