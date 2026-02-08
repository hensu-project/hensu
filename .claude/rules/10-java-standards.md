# 10-java-standards.md

# Hensu Java & Kotlin Standards

This document defines the strict coding standards for the Hensu engine. Adherence ensures
**GraalVM native-image safety** and **multi-tenant isolation**.

---

## Security: ScopedValues Over ThreadLocal

To support high-concurrency Virtual Threads (Project Loom) and safe SaaS deployment, **`ScopedValues`** are mandatory
for context propagation.

* **RULE:** Never use `ThreadLocal`. It leads to memory leaks and context pollution in Virtual Thread pools.
* **PATTERN:** Use `ScopedValue.where(CONTEXT, value).run(() -> { ... })` for passing tenant IDs, security tokens, and
  workflow state.
* **ISOLATION:** Rigorously verify that no data "bleeds" between workflow nodes during parallel execution.

---

## Language Standards

Hensu leverages the latest JVM features to reduce boilerplate and increase type safety.

### **Java 25+ (Core & Server)**

* **Sealed Hierarchies:** Use `sealed interface` for all domain results (e.g., `ExecutionResult`, `TransitionStatus`).
* **Pattern Matching:** Use `switch` expression pattern matching for processing node types and workflow events.
* **Immutability:** All domain models must be immutable. Use the builder pattern provided by the `hensu-serialization`
  mixins.

### **Kotlin (DSL Layer)**
* **DslMarker:** Define `@WorkflowDsl` (meta-annotated with `@DslMarker`) and apply it to all Builder classes.
* **Scope Isolation:** This prevents **Scope Leakage**, ensuring that nested builders cannot access methods from parent scopes incorrectly.
  * *Example:* An `agent { ... }` block should not be able to call `node { ... }` from the parent graph.
* **Builder Pattern:** Annotate `WorkflowBuilder`, `GraphBuilder`, and `NodeBuilder` with `@WorkflowDsl` to enforce this boundary.
* **Context Receivers:** Use context receivers where appropriate for cleaner DSL building logic.

---

## Native-Image Constraints (The "No-Go" List)

Since Hensu is optimized for **GraalVM**, you must avoid the following "dynamic" patterns:

* **No Unregistered Reflection:** Never use `Class.forName()` or `method.invoke()` unless the class is explicitly
  registered in Quarkus build-time metadata.
* **No Dynamic Proxies:** Avoid libraries that generate bytecode at runtime (e.g., standard CGLIB or Hibernate
  lazy-loading).
* **No Classpath Scanning:** All `AgentProvider` and `NodeExecutor` instances must be wired explicitly via
  `HensuFactory.builder()`.

---

## Testing Integrity

* **Unit Tests:** Must be pure JVM and use `StubAgentProvider` to avoid API costs and network latency.
* **Integration Tests:** Use `@QuarkusTest` for server-side logic and verify native-image compatibility with
  `-Dquarkus.native.enabled=true`.
* **Assertions:** Use **AssertJ** for fluent, readable assertions.
