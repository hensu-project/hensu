# Hensu™ Unified Architecture

**Hensu** separates the **authoring** of AI workflows from their **execution**. Developers describe agent behavior in a
type-safe Kotlin DSL. A compiler produces portable JSON definitions. A stateless, GraalVM native-image server executes
them. No user code ever runs on the server.

**Two layers, strictly decoupled:**

| Layer             | Responsibility                                   | Technology                            |
|:------------------|:-------------------------------------------------|:--------------------------------------|
| **Definition**    | Author and compile workflow logic                | Kotlin DSL → JSON artifacts           |
| **Orchestration** | Execute compiled workflows with tenant isolation | Pure Java core + Quarkus native image |

**Workflows operate at two levels:**

- **Macro-Graph:** The static, declarative flow defined in the DSL — which nodes run, in what order, with what
  transitions. This is the **strategy**.
- **Micro-Plan:** The dynamic, step-by-step execution logic within a single node — tool calls, replanning, reflection
  loops. This is the **tactics**.

**The Architectural Core:** The engine is **pure Java** with **zero external dependencies**. Protocol handling (MCP),
provider integrations (LLMs), persistence, and security (multi-tenancy via `ScopedValues`) are pluggable modules wired
explicitly via `HensuFactory.builder()`.

---

## Key Architectural Decisions

### 1. Client-Side Compilation (Terraform/kubectl Pattern)

The server is deployed as a **GraalVM native image** - it cannot include the Kotlin DSL compiler.
Workflow compilation happens on the developer machine:

```
Developer Machine                         Server (Native Image)
—————————————————                         ————————————————————
workflow.kt
    │
    V
hensu build workflow.kt
    │ (compile to JSON)
    V
working-dir/build/{id}.json
    │
    V
hensu push <workflow-id> ——————————————> POST /api/v1/workflows
    │ (reads compiled JSON)                     │
    │                                           V
    │                                    +—————————————+
    │                                    │  Database   │
    │                                    │ (workflows) │
    │                                    +——————+——————+
    │                                           │
hensu pull <workflow-id> <—————————————— GET /api/v1/workflows/{id}
hensu delete <workflow-id> ————————————> DELETE /api/v1/workflows/{id}
hensu list <———————————————————————————— GET /api/v1/workflows
    │
    │  (clients execute via)
    +——————————————————————————————————> POST /api/v1/executions
```

### 2. Centralized Bootstrap (`HensuFactory`)

All core components are assembled through a single builder — `HensuFactory.builder()` — that produces an immutable
`HensuEnvironment` container. This enforces a consistent wiring strategy across both CLI and Server deployments:
agent providers, action executors, repositories, and configuration are resolved once at startup.

The builder is the only place where deployment-specific behavior diverges: the CLI wires a local bash executor and
the LangChain4j provider; the server wires an MCP-only executor and delegates all components via CDI producers.

See [Core Developer Guide](developer-guide-core.md) for usage patterns.

### 3. Zero-Trust Execution (MCP Only)

The server is a **pure orchestrator** — it has no shell, no `eval`, no script runner. All side effects
(tool calls, database writes, API requests) are routed to tenant-owned MCP servers via the **Split-Pipe**
transport:

- **Downstream (SSE):** The server pushes JSON-RPC tool requests to the connected tenant client.
- **Upstream (HTTP POST):** The client executes the tool locally and returns the result.

This means tenant clients connect *outbound* — no inbound ports, no firewall rules, no VPN.
The server never sees raw credentials or executes user-supplied code. LLM output is treated with
equal suspicion — `AgentOutputValidator` sanitizes all agent responses for control characters,
Unicode manipulation, and excessive payload size before the output is written to workflow state.

### 4. Non-Linear Graph Execution

Workflows are not limited to linear chains. The graph engine supports:

| Capability                | Mechanism                                                                                                  |
|:--------------------------|:-----------------------------------------------------------------------------------------------------------|
| **Conditional branching** | `ScoreTransition` routes based on rubric scores; `SuccessTransition` / `FailureTransition` route on result |
| **Loops**                 | `LoopNode` with configurable break conditions and max iterations                                           |
| **Parallel fan-out**      | `ParallelNode` executes branches concurrently on virtual threads                                           |
| **Fork / Join**           | `ForkNode` spawns independent parallel paths; `JoinNode` awaits and merges results                         |
| **Consensus**             | Majority vote, unanimous, weighted vote, or judge-decides strategies                                       |
| **Backtracking**          | Review decisions can jump to any previous node, restoring state from execution history                     |
| **Sub-workflows**         | `SubWorkflowNode` with input/output mapping for hierarchical composition                                   |
| **Pause / Resume**        | Any node returning `PENDING` checkpoints state; `executeFrom()` resumes from snapshot                      |

For non-agent steps, `GenericNode` runs custom synchronous logic registered by `executorType`;
`ActionNode` dispatches asynchronous tasks to external systems via a registered `ActionHandler`
(e.g., webhooks, git operations, notifications).

### 5. Quality Gates (Rubric Evaluation)

Node outputs can be evaluated against markdown rubric definitions before the workflow transitions. The
`RubricEngine` scores outputs on configurable dimensions, and `ScoreTransition` rules route based on
thresholds — enabling self-correcting loops where low-scoring outputs are sent back for revision.

### 6. Storage Architecture

Repository interfaces and in-memory defaults live in **hensu-core**:

- `WorkflowRepository` (`io.hensu.core.workflow`) — Tenant-scoped workflow definition storage
- `WorkflowStateRepository` (`io.hensu.core.state`) — Tenant-scoped execution state snapshots

`HensuFactory.builder()` wires in-memory implementations by default. The server delegates these from
`HensuEnvironment` via `@Produces @Singleton` — it never creates instances directly. Production deployments can
substitute database-backed implementations through the builder.

### 7. Distributed Execution & Recovery

In a multi-instance deployment, each server node holds a **lease** on the executions it is
currently running. Leases are tracked via two columns in `hensu.execution_states`:

- `server_node_id` — the UUID of the server node owning the execution (`NULL` when idle or complete)
- `last_heartbeat_at` — timestamp last refreshed by the owning node

Three components implement the lease lifecycle:

| Component               | Responsibility                                                                                                                                                               |
|:------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ExecutionLeaseManager` | Acquires, renews, and atomically claims leases; generates and holds `server_node_id`                                                                                         |
| `ExecutionHeartbeatJob` | Runs every `hensu.lease.heartbeat-interval` (default `30s`) — bumps `last_heartbeat_at` for all active leases on this node                                                   |
| `WorkflowRecoveryJob`   | Runs every `hensu.lease.recovery-interval` (default `60s`) — claims any execution whose heartbeat is older than `hensu.lease.stale-threshold` (default `90s`) and resumes it |

```
+——————————————————————————————————————————————————————————————————————+
│  save("checkpoint") ————————————————> server_node_id set             │
│       │                                                              │
│       V             every 30 s                                       │
│  updateHeartbeats() ————————————————> last_heartbeat_at = NOW()      │
│       │                                                              │
│  node crashes                  after stale threshold                 │
│       V                                                              │
│  claimStaleExecutions() ———————————> new node claims orphaned row    │
│       V                                                              │
│  resumeExecution() → save("completed") ——> server_node_id = NULL     │
+——————————————————————————————————————————————————————————————————————+
```

**Concurrency safety**: `claimStaleExecutions` uses a single `UPDATE … WHERE last_heartbeat_at < threshold
RETURNING …`. Under PostgreSQL's default `READ COMMITTED` isolation, two concurrent sweepers racing on the
same stale row cannot both claim it — the second re-evaluates the `WHERE` clause against the committed row
(fresh heartbeat) and silently skips it. No application-level locking is required.

The lease is **automatically cleared** (set to `NULL`) when an execution reaches a terminal state —
`"completed"`, `"paused"` (human review), `"failed"`, or `"rejected"`. The `%inmem` test profile
disables the scheduler entirely (`%inmem.quarkus.scheduler.enabled=false`).

### 8. REST API Separation

All path/query identifiers are validated by `@ValidId`; workflow request bodies by `@ValidWorkflow`
(deep-validates the entire object graph for safe identifiers and control-character-free text);
free-text inputs by `@ValidMessage`. `LogSanitizer` strips CR/LF at every log call site.
Violations return `400 Bad Request`. See [Server Developer Guide — Input Validation](developer-guide-server.md#input-validation).

```
/api/v1/workflows    → WorkflowResource (definition management - CLI integration)
├── POST   /                    Push workflow (create/update)
├── GET    /                    List workflows
├── GET    /{workflowId}        Pull workflow
└── DELETE /{workflowId}        Delete workflow

/api/v1/executions   → ExecutionResource (runtime operations - client integration)
├── POST   /                          Start execution (202 Accepted — async; progress via SSE)
├── GET    /{executionId}             Get execution status
├── GET    /{executionId}/events      Subscribe to execution events (SSE stream)
├── POST   /{executionId}/resume      Resume paused execution
├── GET    /{executionId}/plan        Get pending plan
├── GET    /{executionId}/result      Get final output (public context, _-keys stripped)
└── GET    /paused                    List paused executions
```

---

## Architecture Overview

```
+—————————————————————————————————————————————————————————————————————————————+
│                              hensu-server                                   │
│  +————————————————+  +————————————————+  +————————————————+                 │
│  │  Quarkus API   │  │  MCP Gateway   │  │  LLM Planner   │                 │
│  │  (REST/SSE)    │  │  (JSON-RPC)    │  │  (Plan Gen)    │                 │
│  +———————+————————+  +———————+————————+  +———————+————————+                 │
│          │                   │                   │                          │
│  +———————+———————————————————+———————————————————+———————————————————————+  │
│  │                        Server Runtime                                 │  │
│  │  +——————————————+  +——————————————+  +——————————————+                 │  │
│  │  │ ServerAction │  │ AgenticNode  │  │ TenantContext│                 │  │
│  │  │ Executor     │  │ Executor     │  │ (ScopedValue)│                 │  │
│  │  +——————————————+  +——————————————+  +——————————————+                 │  │
│  +———————————————————————————————————————————————————————————————————————+  │
│                                    │                                        │
│  +—————————————————————————————————+—————————————————————————————————————+  │
│  │                     hensu-core (HensuEnvironment)                     │  │
│  │                                                                       │  │
│  │  +—————————————+  +—————————————+  +—————————————+  +—————————————+   │  │
│  │  │  Workflow   │  │    Node     │  │    Plan     │  │   Action    │   │  │
│  │  │  Executor   │  │  Executors  │  │   Engine    │  │  Executor   │   │  │
│  │  +—————————————+  +—————————————+  +—————————————+  +—————————————+   │  │
│  │                                                                       │  │
│  │  +—————————————+  +—————————————+  +—————————————+  +—————————————+   │  │
│  │  │   Rubric    │  │    Tool     │  │   Agent     │  │   Event     │   │  │
│  │  │   Engine    │  │  Registry   │  │  Registry   │  │    Bus      │   │  │
│  │  +—————————————+  +—————————————+  +—————————————+  +—————————————+   │  │
│  │                                                                       │  │
│  │  +—————————————+  +——————————————————————————————+                    │  │
│  │  │  Workflow   │  │  WorkflowState               │                    │  │
│  │  │  Repository │  │  Repository                  │                    │  │
│  │  +—————————————+  +——————————————————————————————+                    │  │
│  +———————————————————————————————————————————————————————————————————————+  │
+—————————————————————————————————————————————————————————————————————————————+
                                     │
                          MCP Protocol (JSON-RPC)
                                     │
                +————————————————————+————————————————————+
                │                    │                    │
                V                    V                    V
        +—————————————————+  +—————————————————+  +—————————————————+
        │  Customer MCP   │  │  Customer MCP   │  │  Customer MCP   │
        │  Server (Tools) │  │  Server (Data)  │  │  Server (Auth)  │
        +—————————————————+  +—————————————————+  +—————————————————+
```

---

## Module Structure

### hensu-core (Pure Execution Runtime)

Zero-dependency Java library. Contains:

- `HensuFactory` / `HensuEnvironment` — Builder and container for all core components
- `WorkflowExecutor` — Graph traversal, node dispatch, pause/resume via `executeFrom()`
- `NodeExecutorRegistry` — Pluggable node type executors
- `AgentRegistry` / `AgentFactory` — Agent management with explicit provider wiring
- `ActionExecutor` — Pluggable action dispatch (Send/Execute)
- `PlanExecutor` — Step-by-step plan execution
- `ToolRegistry` / `ToolDefinition` — Protocol-agnostic tool descriptors for MCP integration
- `RubricEngine` — Quality evaluation (rubrics embedded in workflow JSON)
- `WorkflowRepository` / `WorkflowStateRepository` — Tenant-scoped storage interfaces with in-memory defaults
- `HensuState` / `HensuSnapshot` / `ExecutionHistory` — Mutable runtime state, immutable checkpoints, execution trace
- Workflow model, Node types (including `SubWorkflowNode`), Transition rules

### hensu-dsl (Kotlin DSL)

Kotlin DSL for workflow definitions. Contains:

- `HensuDSL.kt` - Top-level `workflow { }` entry point
- `KotlinScriptParser` - Compiles `.kt` files via embedded Kotlin compiler
- Type-safe builders for all node types, transitions, and configurations
- `Models` constants for supported AI model identifiers

**Client-side only.** Never runs on the server (no Kotlin compiler in native image).

### hensu-server (Quarkus Native Image)

Extends core with HTTP, MCP, and multi-tenancy:

- `HensuEnvironmentProducer` — CDI producer using `HensuFactory.builder()`
- `ServerConfiguration` — Delegates core components from `HensuEnvironment` via `@Produces @Singleton`
- `ServerActionExecutor` — MCP-only action executor (rejects local execution)
- `WorkflowService` — Service layer: start/resume executions, snapshot management
- `WorkflowResource` — Workflow definition management (push/pull/delete/list)
- `ExecutionResource` — Execution runtime (start/resume/status/plan)
- `McpSidecar` / `McpGateway` — MCP protocol integration
- `LlmPlanner` — LLM-based plan generation
- `TenantContext` — Java 25 `ScopedValue` carrying tenant identity for the scope of a request; `TenantContext.runAs()` is the safe propagation entry point
- `ExecutionLeaseManager` / `ExecutionHeartbeatJob` / `WorkflowRecoveryJob` — Distributed recovery: heartbeat emission and orphaned-execution sweeper
- `JdbcWorkflowRepository` / `JdbcWorkflowStateRepository` — PostgreSQL-backed storage (JSONB workflow definitions, execution state + lease columns)

### hensu-serialization (JSON Serialization)

Jackson-based JSON serialization shared by CLI and server:

- `WorkflowSerializer` - Entry point: `toJson()`, `fromJson()`, `createMapper()`
- `HensuJacksonModule` - Custom serializers/deserializers for Node, TransitionRule, Action type hierarchies
- Jackson mixins for builder-based deserialization (Workflow, AgentConfig)
- GraalVM-safe: explicit registrations via `SimpleModule` (no reflective scanning)

### hensu-cli (Quarkus CLI)

Developer-facing CLI tool:

- Uses `hensu-dsl` for Kotlin DSL compilation (workflow.kt → JSON)
- `hensu build` - Compile DSL to JSON (`{working-dir}/build/`)
- `hensu push` / `pull` / `delete` / `list` - Server workflow management
- Local execution mode (uses full HensuEnvironment with local action executor)
- `HensuEnvironmentProducer` (CLI variant - wires LangChain4jProvider, bash execution)

---

## Core Concepts

### 1. Macro-Graph (DSL Level)

The static workflow defined by users:

```kotlin
workflow("OrderProcessing") {
    agents { ... }

    graph {
        start at "validate"

        node("validate") { ... }
        node("process") { ... }
        node("notify") { ... }

        end("complete")
    }
}
```

### 2. Micro-Plan (Node Level)

Internal execution strategy within a node. Two modes:

**Predefined Plan (Static):**

```kotlin
node("process-order") {
    agent = "processor"

    plan {
        step("get_order", mapOf("id" to "{orderId}"))
        step("validate_payment", mapOf("amount" to "{order.total}"))
        step("reserve_inventory", mapOf("items" to "{order.items}"))
        step("confirm_order", mapOf("id" to "{orderId}"))
    }

    onSuccess goto "notify"
    onPlanFailure goto "manual-review"
}
```

**Dynamic Plan (LLM-Generated):**

```kotlin
node("research-topic") {
    agent = "researcher"
    tools = listOf("search", "analyze", "summarize")

    planning {
        mode = PlanningMode.DYNAMIC
        maxSteps = 5
        allowReplan = true
        reviewBeforeExecute = false
    }

    prompt = "Research {topic} comprehensively"
    onSuccess goto "publish"
    onPlanFailure goto "fallback"
}
```

### 3. The Execution Loop

`WorkflowExecutor` separates every node traversal into two distinct concerns: the **outer
processor pipeline** that wraps each node, and the **inner plan-step loop** within it.

**Outer Pipeline** (`ProcessorPipeline`) — every node traversal:

```
+——————————————————————————————————————————————————+
│  PRE-EXECUTION PIPELINE                          │
│  1. CheckpointPreProcessor  (persist state)      │
│  2. NodeStartPreProcessor   (observability hook) │
+—————————————————————+————————————————————————————+
                      V
            node.execute() ————> Inner Loop (see below)
                      V
+——————————————————————————————————————————————————+
│  POST-EXECUTION PIPELINE                         │
│  1. OutputExtractionPostProcessor                │
│     (AgentOutputValidator → write to context)    │
│  2. NodeCompletePostProcessor (observability)    │
│  3. HistoryPostProcessor      (audit trail)      │
│  4. ReviewPostProcessor       (human-in-the-loop)│
│  5. RubricPostProcessor       (quality gate)     │
│  6. TransitionPostProcessor   (next node)        │
+——————————————————————————————————————————————————+
```

Any processor can short-circuit by returning a terminal `ExecutionResult`.

**Inner Plan Loop** — what `node.execute()` runs for `StandardNode`:

```
+———————————————————————————————————————————————————————————————+
│                     Node Execution                            │
│                                                               │
│  +—————————+     +—————————————+     +—————————————————————+  │
│  │  Goal   │————>│   Planner   │————>│       Plan          │  │
│  │ (Prompt)│     │(Static/LLM) │     │ [S1, S2, S3, ...]   │  │
│  +—————————+     +—————————————+     +——————————+——————————+  │
│                                                 │             │
│                 +———————————————————————————————+             │
│                 │                                             │
│                 V                                             │
│  +—————————————————————————————————————————————————————————+  │
│  │                   Execution Loop                        │  │
│  │                                                         │  │
│  │      +————————+     +————————+     +————————+           │  │
│  │      │Execute │————>│Observe │————>│Reflect │——+        │  │
│  │      │ Step   │     │ Result │     │        │  │        │  │
│  │      +————————+     +————————+     +————————+  │        │  │
│  │        ^                                       │        │  │
│  │        │                                       │        │  │
│  │        +———————————————————————————————————————+        │  │
│  │                    (next step or replan)                │  │
│  +———————————————————————————+—————————————————————————————+  │
│                              │                                │
│                              V                                │
│                    +—————————————————+                        │
│                    │  Final Output   │                        │
│                    +—————————————————+                        │
+———————————————————————————————————————————————————————————————+
```

---

## Server Initialization

The server wires core infrastructure through CDI:

```
+—————————————————————————————————————————————————————————————+
│ HensuEnvironmentProducer (@ApplicationScoped)               │
│                                                             │
│  1. Extracts hensu.* properties from Quarkus config         │
│  2. Injects ServerActionExecutor (MCP-only)                 │
│  3. Builds via HensuFactory.builder()                       │
│  4. Registers generic node handlers                         │
│  5. Produces HensuEnvironment bean                          │
+——————————————————————+——————————————————————————————————————+
                       │
                       V
+—————————————————————————————————————————————————————————————+
│ ServerConfiguration (@Singleton producers)                  │
│                                                             │
│  Delegates ALL core components from HensuEnvironment:       │
│  - WorkflowExecutor (from env)                              │
│  - AgentRegistry (from env)                                 │
│  - NodeExecutorRegistry (from env)                          │
│  - PlanExecutor (from env)                                  │
│  - WorkflowRepository (from env, defaults to InMemory)      │
│  - WorkflowStateRepository (from env, defaults to InMemory) │
│                                                             │
│  Produces server-specific beans:                            │
│  - ObjectMapper, LlmPlanner, McpConnectionFactory           │
+—————————————————————————————————————————————————————————————+
```

---

## GraalVM Design Constraints

Hensu is deployed as a **GraalVM native image** — this is not just a deployment detail, it shapes
core architecture. GraalVM performs static analysis at build time; patterns that require runtime
reflection, classpath scanning, or dynamic class generation fail silently or crash.

### The No-Go List (hensu-core)

| Pattern                                     | Problem                              | Rule                                          |
|:--------------------------------------------|:-------------------------------------|:----------------------------------------------|
| `Class.forName()` / `field.setAccessible()` | Requires runtime reflection metadata | Never in `hensu-core`                         |
| `Proxy.newProxyInstance()`                  | Generates classes at runtime         | Never                                         |
| Jackson `@JsonTypeInfo(use = CLASS)`        | Encodes class names as strings       | Never; use `SimpleModule` type discriminators |
| Classpath scanning / `ServiceLoader`        | Scans at runtime                     | Explicit wiring via `HensuFactory.builder()`  |

### Quarkus Relaxations (hensu-server)

Quarkus extensions generate GraalVM metadata at build time, so these patterns work safely
within `hensu-server`:

- CDI injection (`@Inject`, `@Produces`) — ArC resolves beans at build time
- `@ConfigProperty` — processed at build time
- JAX-RS resources (`@Path`, `@GET`) — REST layer is build-time wired
- LangChain4j AI services — `quarkus-langchain4j` extensions register metadata

### Explicit Wiring as a Design Principle

The prohibition on classpath scanning is why `HensuFactory.builder()` uses explicit wiring.
`AgentProvider`, `NodeExecutorRegistry`, and `ActionExecutor` instances are declared at call
sites — GraalVM's static analysis can follow every reference.

Classes in `hensu-core` that Jackson needs reflectively (builder constructors, setter methods)
are registered in `NativeImageConfig` in `hensu-server` via `@RegisterForReflection`. No
Quarkus or Jackson annotations ever enter `hensu-core`.

See [Server Developer Guide — GraalVM Native Image](developer-guide-server.md#graalvm-native-image).

---

## Jackson Serialization Contract

### The Core Boundary Rule

`hensu-core` contains **zero Jackson imports**. Domain models (`Workflow`, `Node`, `AgentConfig`)
are plain Java records and builder classes — no `@JsonProperty`, `@JsonDeserialize`,
`@JsonTypeInfo`. This is a deliberate decoupling contract: the core engine is a pure Java
library, testable and deployable without any JSON framework.

### How Serialization Is Wired

`hensu-serialization` owns the entire Jackson configuration:

| Component            | Role                                                                                                                                        |
|:---------------------|:--------------------------------------------------------------------------------------------------------------------------------------------|
| `WorkflowSerializer` | Entry point: `toJson()`, `fromJson()`, `createMapper()` — the single `ObjectMapper` factory                                                 |
| `HensuJacksonModule` | `SimpleModule` registering all custom serializers/deserializers for `Node`, `TransitionRule`, `Action` hierarchies — no reflective scanning |
| `mixin/` package     | Jackson mixins enabling builder-based deserialization without annotating core models                                                        |

`WorkflowSerializer.createMapper()` is the **single `ObjectMapper` factory** for CLI and server.
`ServerConfiguration` exposes it as a CDI bean via `@Produces @Singleton`.

### GraalVM Implication

Jackson mixins are a runtime event — Quarkus cannot trace them at build time. `NativeImageConfig`
in `hensu-server` is the single `@RegisterForReflection` registration point for all `hensu-core`
builder classes that the mixin machinery needs.

See [hensu-serialization Developer Guide](developer-guide-serialization.md) for the `treeToValue` rule.

---

## Testing Strategy

Each layer has a dedicated testing approach exercising real code at the appropriate scope.

### Unit Tests (Pure JVM)

Isolated class tests using Mockito. `StubAgentProvider` (priority 1000) intercepts all agent
creation and returns a `StubAgent` backed by `StubResponseRegistry`. No AI API calls, no network,
no containers.

### Integration Tests (Quarkus InMemory)

`@QuarkusTest` with `@TestProfile(InMemoryTestProfile.class)` boots the full server — API,
CDI wiring, `WorkflowExecutor`, `TenantContext` — against in-memory repositories. The `inmem`
profile disables PostgreSQL, Flyway, and the scheduler (no Docker required).

All integration tests extend `IntegrationTestBase`, which provides CDI injection, per-test
state cleanup, and helpers (`registerStub`, `pushAndExecute`, `resolveRubricPath`).

### Repository Tests (Testcontainers PostgreSQL)

Tests in `io.hensu.server.persistence` extend `JdbcRepositoryTestBase`, which starts a real
PostgreSQL container and runs Flyway migrations — no Quarkus context involved. These tests
cover CRUD, UPSERT semantics, FK constraints, tenant isolation, lease column behaviour, and
distributed recovery operations.

### Test Coverage Map

| Layer       | Mechanism                      | Scope                                                |
|:------------|:-------------------------------|:-----------------------------------------------------|
| Unit        | Mockito, pure JVM              | Class-level logic, edge cases                        |
| Integration | `@QuarkusTest` + inmem profile | CDI wiring, API contracts, end-to-end workflow logic |
| Persistence | Testcontainers + Flyway        | SQL correctness, schema migrations, tenant isolation |

---

## Summary

The unified architecture provides:

1. **Pure Core** — Zero-dependency Java engine, protocol-agnostic
2. **Build-Then-Push** — Client-side compilation (Kotlin DSL → JSON); server receives pre-compiled artifacts
3. **Centralized Bootstrap** — `HensuFactory.builder()` as the single entry point for all core infrastructure
4. **Zero-Trust Execution** — Server has no shell; all side effects route through MCP to tenant clients
5. **Non-Linear Graphs** — Loops, conditional branches, fork/join, parallel fan-out with consensus, backtracking
6. **Rubric Evaluation** — Quality gates that score outputs and route on thresholds for self-correcting loops
7. **Pause / Resume** — Workflows checkpoint at any node and resume; the lease protocol protects against data races when the owning node crashes
8. **Distributed Recovery** — Heartbeat/sweeper lease protocol for crashed-node detection; atomic PostgreSQL `UPDATE…RETURNING` claim
9. **Sub-Workflows** — Hierarchical composition via `SubWorkflowNode` with input/output mapping
10. **Flexible Planning** — Static (predefined) or Dynamic (LLM-generated) execution plans within nodes
11. **Human Review** — Checkpoints for manual approval at both plan and execution levels
12. **Multi-Tenancy** — Java 25 `ScopedValues` for safe tenant context propagation and isolation
13. **Storage in Core** — Repository interfaces with in-memory defaults; server delegates via CDI
14. **Shared Serialization** — `hensu-serialization` provides consistent JSON format; zero Jackson in `hensu-core`
15. **API Separation** — Workflow definitions and executions are distinct REST resources
16. **GraalVM-First Design** — No-reflection core; explicit wiring enables static analysis
17. **Three-Layer Testing** — Unit (Mockito), Integration (inmem + stubs), Persistence (Testcontainers)
