# Javadoc as Structured Metadata

This guide treats Javadoc as a **structured metadata layer** for AI coding assistants, static analysis tools, and
automated refactoring engines. Targeting **Java 25** with Markdown Javadoc (`///`).

---

## 1. Core Philosophy: Documentation as Data

Every Javadoc block serves two audiences:

- **Human Developer**: Needs "why" and "how" quickly
- **AI Agent / Tooling**: Needs boundaries, side effects, contracts, and constraints to prevent hallucination

**Principle**: If a machine can't parse it, it's incomplete documentation.

---

## 2. Markdown Javadoc Syntax (Java 23+)

Use `///` for all new code. More readable for LLMs than legacy HTML.

| Feature        | Legacy                    | Markdown (`///`)              |
|:---------------|:--------------------------|:------------------------------|
| **Lists**      | `<ul><li>...</li></ul>`   | `- Item`                      |
| **Code**       | `{@code myMethod()}`      | `` `myMethod()` ``            |
| **Paragraphs** | `<p>Text</p>`             | Blank line                    |
| **Bold**       | `<b>text</b>`             | `**text**`                    |
| **Links**      | `{@link Class#method}`    | `{@link Class#method}` (same) |
| **Headings**   | Not supported             | `### Heading`                 |

---

## 3. Standard Javadoc Tags Reference

### Core Tags (Always Use)

| Tag                 | Purpose                  | Machine Use                      |
|:--------------------|:-------------------------|:---------------------------------|
| `@param name`       | Parameter description    | Input validation, type inference |
| `@return`           | Return value description | Output expectation               |
| `@throws Exception` | Failure conditions       | Error boundary detection         |
| `@see`              | Cross-references         | Dependency graph                 |

### Java 21+ Tags

| Tag               | Purpose                                    | Example                             |
|:------------------|:-------------------------------------------|:------------------------------------|
| `@apiNote`        | Usage guidance, non-contractual            | "Prefer `newMethod()` for new code" |
| `@implNote`       | Implementation detail, may change          | "Uses ConcurrentHashMap internally" |
| `@implSpec`       | Implementation requirements for subclasses | "Subclasses must call super"        |
| `@spec URL title` | Link to external specification             | `@spec https://spec.org RFC-1234`   |

### Snippet Tag (Java 21+)

```java
/// Example usage:
/// {@snippet :
/// var executor = new WorkflowExecutor(registry, agents);
/// executor.execute(workflow, context);
/// }
```

Snippets are **compiled and validated** - dead code is caught at build time.

---

## 4. Structured Metadata Sections

Use markdown headings within Javadoc for machine-parseable sections:

### 4.1 Contracts

```java
/// Executes the workflow from start to end node.
///
/// ### Contracts
/// - **Precondition**: `workflow.getStartNode()` must exist in node map
/// - **Postcondition**: Returns `Completed` or `Rejected`, never null
/// - **Invariant**: State history is append-only during execution
///
/// @param workflow the workflow definition, not null
/// @param context initial context variables, not null
/// @return execution result, never null
/// @throws IllegalStateException if start node not found
```

### 4.2 Thread Safety

Use annotations when available, document in `@implNote`:

```java
/// Manages registered agents for workflow execution.
///
/// @implNote Thread-safe. Uses ConcurrentHashMap for agent storage.
/// All public methods can be called from any thread.
public class DefaultAgentRegistry implements AgentRegistry {
```

For non-thread-safe classes:

```java
/// @implNote **Not thread-safe**. External synchronization required
/// for concurrent access. Designed for single-threaded workflow execution.
```

### 4.3 Side Effects

Document observable effects in `@apiNote`:

```java
/// Registers an agent with the given ID.
///
/// @apiNote **Side effects**:
/// - Modifies internal agent registry
/// - Overwrites existing agent if ID already registered
/// - Logs registration at INFO level
///
/// @param id unique identifier, not null or blank
/// @param agent the agent instance, not null
```

### 4.4 Layer Boundary Markers

Layer rules are **package-wide** — document them once in `AGENTS.md` or `package-info.java`,
not on every class. Per-class Javadoc repeating the package rule is noise.

Use a class-level marker **only** for exceptions: a class that violates the package's expected
layer (e.g., a server-only class in an otherwise neutral package).

```java
/// package-info.java — documents the rule once for the entire package
/// Core model package. No framework dependencies.
/// Safe to reference from hensu-core, hensu-dsl, and hensu-server.
package io.hensu.core.workflow.state;
```

```java
/// Produces CDI beans for the execution pipeline.
///
/// @implNote **Server layer only.** Depends on Quarkus ArC and CDI.
/// Do not reference from `hensu-core` or `hensu-dsl`.
@ApplicationScoped
public class ServerConfiguration { ... }
```

### 4.5 Mutability and Lifecycle

Document the mutability contract explicitly. Immutability after construction is the most
important property for safe sharing across Virtual Threads.

```java
/// @implNote **Immutable after construction.** All fields are final;
/// safe to share across Virtual Threads without synchronization.
public final class WorkflowStateSchema { ... }
```

```java
/// @implNote **Mutable.** Single-execution lifecycle — one instance per
/// workflow run. Not safe for concurrent access without external locking.
public final class HensuState { ... }
```

Required keywords (use exactly):
- `Immutable after construction.` — all fields final, freely shareable
- `Mutable.` — state changes after construction; document lifecycle scope

---

## 5. Nullability Conventions

**Rule**: Every parameter and return value must have explicit nullability.

```java
/// @param id the agent identifier, not null
/// @param config optional configuration, may be null
/// @return the created agent, never null
/// @throws NullPointerException if id is null
```

Prefer annotations alongside Javadoc for tool integration:

```java
public Agent create(@NonNull String id, @Nullable AgentConfig config) {
```

---

## 6. Type-Specific Documentation

### Records

```java
/// Immutable execution step captured during workflow processing.
///
/// @param nodeId the executed node identifier, not null
/// @param result execution outcome, not null
/// @param timestamp when execution completed, not null
public record ExecutionStep(
    String nodeId,
    NodeResult result,
    Instant timestamp
) {}
```

### Sealed Classes

```java
/// Represents the outcome of a workflow execution.
///
/// ### Permitted Subtypes
/// - {@link Completed} - successful end state reached
/// - {@link Rejected} - workflow rejected by review
/// - {@link Failed} - unrecoverable error occurred
///
/// @see #isSuccess() for quick success check
public sealed interface ExecutionResult
    permits Completed, Rejected, Failed {
```

### Functional Interfaces

```java
/// Evaluates a rubric criterion against agent output.
///
/// This is a {@link FunctionalInterface} suitable for lambda expressions:
/// {@snippet :
/// RubricEvaluator eval = (output, criterion) ->
///     output.contains(criterion.keyword()) ? 1.0 : 0.0;
/// }
///
/// @see RubricEngine#evaluate for typical usage
@FunctionalInterface
public interface RubricEvaluator {
```

---

## 7. Anti-Patterns

### ❌ Redundant Documentation

```java
/// Gets the ID.           // Says nothing useful
/// @return the ID         // Repeats method name
public String getId() { return id; }
```

### ✅ Meaningful Documentation

```java
/// Returns the unique identifier used for agent registry lookup.
/// @return non-null identifier, stable for the agent's lifetime
public String getId() { return id; }
```

### ❌ Missing Failure Modes

```java
/// Loads the workflow.
/// @return the workflow
public Workflow load(Path path) { ... }
```

### ✅ Complete Contract

```java
/// Loads and parses a workflow definition from the filesystem.
///
/// @param path path to `.kts` workflow file, not null
/// @return parsed workflow, never null
/// @throws WorkflowNotFoundException if path doesn't exist
/// @throws WorkflowParseException if syntax is invalid
/// @throws IOException if file cannot be read
public Workflow load(Path path) { ... }
```

---

## 8. Quick Reference Template

```java
/// Brief single-line summary ending with period.
///
/// Extended description if needed. Use markdown formatting
/// for **emphasis** and `code references`.
///
/// ### Contracts
/// - **Precondition**: describe caller requirements
/// - **Postcondition**: describe guaranteed outcomes
///
/// @apiNote Usage guidance or recommendations
/// @implNote **Immutable after construction. Thread-safe.** Additional implementation details.
///
/// @param paramName description, nullability statement
/// @return description, nullability statement
/// @throws ExceptionType when this specific condition occurs
/// @see RelatedClass#relatedMethod for related functionality
```

---

## 9. Validation Checklist

Before committing, verify each public API element has:

- [ ] Single-line summary (first sentence)
- [ ] All parameters documented with nullability
- [ ] Return value documented with nullability
- [ ] All thrown exceptions documented with conditions
- [ ] Thread safety stated if stateful
- [ ] Side effects documented if any
- [ ] Layer boundary marker on class-level Javadoc **only** if the class is an exception to its package rule
- [ ] Mutability marker (`Immutable after construction.` / `Mutable.`) on class-level Javadoc
