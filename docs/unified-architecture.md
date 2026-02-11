# Hensu Unified Architecture

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
─────────────────                         ────────────────────
workflow.kt
    │
    ▼
hensu build workflow.kt
    │ (compile to JSON)
    ▼
working-dir/build/{id}.json
    │
    ▼
hensu push <workflow-id> ───────────────► POST /api/v1/workflows
    │ (reads compiled JSON)                   │
    │                                         ▼
    │                                   ┌─────────────┐
    │                                   │  Database   │
    │                                   │ (workflows) │
    │                                   └─────────────┘
    │                                         │
hensu pull <workflow-id> ◄────────────── GET /api/v1/workflows/{id}
hensu delete <workflow-id> ────────────► DELETE /api/v1/workflows/{id}
hensu list ◄─────────────────────────── GET /api/v1/workflows
    │
    │  (clients execute via)
    └────────────────────────────────► POST /api/v1/executions
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
The server never sees raw credentials or executes user-supplied code.

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

### 7. REST API Separation

```
/api/v1/workflows    → WorkflowResource (definition management - CLI integration)
├── POST   /                    Push workflow (create/update)
├── GET    /                    List workflows
├── GET    /{workflowId}        Pull workflow
└── DELETE /{workflowId}        Delete workflow

/api/v1/executions   → ExecutionResource (runtime operations - client integration)
├── POST   /                    Start execution
├── GET    /{executionId}       Get execution status
├── POST   /{executionId}/resume  Resume paused execution
├── GET    /{executionId}/plan    Get pending plan
└── GET    /paused              List paused executions
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              hensu-server                                   │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐                 │
│  │  Quarkus API   │  │  MCP Gateway   │  │  LLM Planner   │                 │
│  │  (REST/SSE)    │  │  (JSON-RPC)    │  │  (Plan Gen)    │                 │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘                 │
│          │                   │                   │                          │
│  ┌───────┴───────────────────┴───────────────────┴───────────────────────┐  │
│  │                        Server Runtime                                 │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                 │  │
│  │  │ ServerAction │  │ AgenticNode  │  │ TenantContext│                 │  │
│  │  │ Executor     │  │ Executor     │  │ (ScopedValue)│                 │  │
│  │  └──────────────┘  └──────────────┘  └──────────────┘                 │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│  ┌─────────────────────────────────┴─────────────────────────────────────┐  │
│  │                     hensu-core (HensuEnvironment)                     │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │  │
│  │  │  Workflow   │  │    Node     │  │    Plan     │  │   Action    │   │  │
│  │  │  Executor   │  │  Executors  │  │   Engine    │  │  Executor   │   │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │  │
│  │  │   Rubric    │  │    Tool     │  │   Agent     │  │   Event     │   │  │
│  │  │   Engine    │  │  Registry   │  │  Registry   │  │    Bus      │   │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌──────────────────────────────┐                    │  │
│  │  │  Workflow   │  │  WorkflowState               │                    │  │
│  │  │  Repository │  │  Repository                  │                    │  │
│  │  └─────────────┘  └──────────────────────────────┘                    │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
│
MCP Protocol (JSON-RPC)
│
┌───────────────────────┼───────────────────────┐
▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│ Customer MCP    │    │ Customer MCP    │    │ Customer MCP    │
│ Server (Tools)  │    │ Server (Data)   │    │ Server (Auth)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
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
- `TenantContext` — `ScopedValue`-based tenant isolation

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

```
┌──────────────────────────────────────────────────────────────┐
│                     Node Execution                           │
│                                                              │
│  ┌─────────┐    ┌─────────────┐    ┌─────────────────────┐   │
│  │  Goal   │───▶│   Planner   │───▶│       Plan          │   │
│  │ (Prompt)│    │(Static/LLM) │    │ [S1, S2, S3, ...]   │   │
│  └─────────┘    └─────────────┘    └──────────┬──────────┘   │
│                                               │              │
│                 ┌─────────────────────────────┘              │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐    │
│  │                   Execution Loop                     │    │
│  │                                                      │    │
│  │   ┌────────┐    ┌────────┐    ┌────────┐             │    │
│  │   │Execute │───▶│Observe │───▶│Reflect │──┐          │    │
│  │   │ Step   │    │ Result │    │        │  │          │    │
│  │   └────────┘    └────────┘    └────────┘  │          │    │
│  │        ▲                                  │          │    │
│  │        └──────────────────────────────────┘          │    │
│  │                    (next step or replan)             │    │
│  └──────────────────────────────────────────────────────┘    │
│                              │                               │
│                              ▼                               │
│                    ┌─────────────────┐                       │
│                    │  Final Output   │                       │
│                    └─────────────────┘                       │
└──────────────────────────────────────────────────────────────┘
```

---

## Server Initialization

The server wires core infrastructure through CDI:

```
┌─────────────────────────────────────────────────────────────┐
│ HensuEnvironmentProducer (@ApplicationScoped)               │
│                                                             │
│  1. Extracts hensu.* properties from Quarkus config         │
│  2. Injects ServerActionExecutor (MCP-only)                 │
│  3. Builds via HensuFactory.builder()                       │
│  4. Registers generic node handlers                         │
│  5. Produces HensuEnvironment bean                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
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
└─────────────────────────────────────────────────────────────┘
```

---

## Summary

The unified architecture provides:

1. **Pure Core** — Zero-dependency Java engine, protocol-agnostic
2. **Build-Then-Push** — Client-side compilation (Kotlin DSL → JSON); server receives pre-compiled artifacts
3. **Centralized Bootstrap** — `HensuFactory.builder()` as the single entry point for all core infrastructure
4. **Zero-Trust Execution** — Server has no shell; all side effects route through MCP to tenant clients
5. **Non-Linear Graphs** — Loops, conditional branches, fork/join, parallel fan-out with consensus, backtracking
6. **Rubric Evaluation** — Quality gates that score outputs and route on thresholds for self-correcting loops
7. **Pause / Resume** — Workflows checkpoint at any node and resume (potentially on a different instance)
8. **Sub-Workflows** — Hierarchical composition via `SubWorkflowNode` with input/output mapping
9. **Flexible Planning** — Static (predefined) or Dynamic (LLM-generated) execution plans within nodes
10. **Human Review** — Checkpoints for manual approval at both plan and execution levels
11. **Multi-Tenancy** — `ScopedValues` for safe context propagation and tenant isolation
12. **Storage in Core** — Repository interfaces with in-memory defaults; server delegates via CDI
13. **Shared Serialization** — `hensu-serialization` provides consistent JSON format for CLI and server
14. **API Separation** — Workflow definitions and executions are distinct REST resources
