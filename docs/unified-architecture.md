Hensu Unified Architecture

Vision Statement

Hensu is an Autonomous Orchestration Server that executes AI agent workflows. The core engine is pure Java with zero external
dependencies. Protocol handling (MCP), LLM integration, and multi-tenancy live at the server layer.

Workflows operate at two levels:
- Macro-Graph: Static flow defined in Kotlin DSL (business logic)
- Micro-Plan: Dynamic or predefined steps within a node (task execution)

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

### 2. HensuFactory → HensuEnvironment Pattern

**ALWAYS** use `HensuFactory.builder()` to create core infrastructure:

```java
// Correct approach - both CLI and Server
HensuEnvironment env = HensuFactory.builder()
    .config(HensuConfig.builder().useVirtualThreads(true).build())
    .loadCredentials(properties)
    .actionExecutor(customExecutor)  // Server provides MCP-only executor
    .build();

// Access components from environment
WorkflowExecutor executor = env.getWorkflowExecutor();
AgentRegistry agents = env.getAgentRegistry();
```

**NEVER** bypass HensuFactory:
```java
// WRONG - bypasses configuration, stub mode, SPI discovery
WorkflowExecutor executor = new WorkflowExecutor(nodeRegistry, actionHandler);
```

### 3. Server Execution Model (MCP Only)

The server **NEVER executes commands locally**. It only sends requests to external MCP servers:

```java
// ServerActionExecutor - server-specific implementation
@Override
public ActionResult execute(Action action, Map<String, Object> context) {
    return switch (action) {
        case Action.Send send -> executeSend(send, context);  // MCP tool calls
        case Action.Execute exec -> ActionResult.failure(
            "Server mode does not support local command execution");
    };
}
```

### 4. Storage Separation

- **hensu-core**: Pure execution runtime (no persistence interfaces)
- **hensu-server/persistence/**: Server-specific storage
  - `WorkflowRepository` - Workflow definition storage (push/pull/delete)
  - `WorkflowStateRepository` - Execution state storage (snapshots)

### 5. REST API Separation

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
│  │  ┌──────────────┐  ┌──────────────┐                                   │  │
│  │  │ Workflow     │  │ WorkflowState│                                   │  │
│  │  │ Repository   │  │ Repository   │                                   │  │
│  │  └──────────────┘  └──────────────┘                                   │  │
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
│  │  │   State     │  │    Tool     │  │   Agent     │  │   Event     │   │  │
│  │  │  Manager    │  │  Registry   │  │  Registry   │  │    Bus      │   │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │  │
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
- `HensuFactory` / `HensuEnvironment` - Builder and container for core components
- `WorkflowExecutor` - Graph traversal and node dispatch
- `NodeExecutorRegistry` - Pluggable node type executors
- `AgentRegistry` / `AgentFactory` - Agent management with explicit provider wiring
- `ActionExecutor` - Pluggable action dispatch (Send/Execute)
- `PlanExecutor` - Step-by-step plan execution
- `ToolRegistry` / `ToolDefinition` - Protocol-agnostic tool descriptors for MCP integration
- `RubricEngine` - Quality evaluation (rubrics embedded in workflow JSON)
- Workflow model, Node types, Transition rules

**Core has NO persistence.** It is a pure execution runtime.

### hensu-dsl (Kotlin DSL)

Kotlin DSL for workflow definitions. Contains:
- `HensuDSL.kt` - Top-level `workflow { }` entry point
- `KotlinScriptParser` - Compiles `.kt` files via embedded Kotlin compiler
- Type-safe builders for all node types, transitions, and configurations
- `Models` constants for supported AI model identifiers

**Client-side only.** Never runs on the server (no Kotlin compiler in native image).

### hensu-server (Quarkus Native Image)

Extends core with HTTP, MCP, multi-tenancy, and persistence:
- `HensuEnvironmentProducer` - CDI producer using HensuFactory.builder()
- `ServerActionExecutor` - MCP-only action executor (rejects local execution)
- `ServerConfiguration` - CDI delegation of HensuEnvironment components
- `WorkflowResource` - Workflow definition management (push/pull/delete/list)
- `ExecutionResource` - Execution runtime (start/resume/status/plan)
- `WorkflowRepository` / `WorkflowStateRepository` - Server-specific persistence
- `McpSidecar` / `McpGateway` - MCP protocol integration
- `LlmPlanner` - LLM-based plan generation
- `TenantContext` - ScopedValue-based tenant isolation

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
│  │        ▲                                   │         │    │
│  │        └───────────────────────────────────┘         │    │
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
│ ServerConfiguration (@ApplicationScoped)                     │
│                                                             │
│  Delegates from HensuEnvironment for CDI injection:         │
│  - WorkflowExecutor (from env)                              │
│  - AgentRegistry (from env)                                 │
│  - NodeExecutorRegistry (from env)                          │
│  - PlanExecutor (from env)                                  │
│                                                             │
│  Produces server-specific beans:                            │
│  - WorkflowRepository (InMemory for MVP)                    │
│  - WorkflowStateRepository (InMemory for MVP)               │
│  - ObjectMapper, LlmPlanner, McpConnectionFactory           │
└─────────────────────────────────────────────────────────────┘
```

---

## Summary

The unified architecture provides:

1. **Pure Core** - No external dependencies, protocol-agnostic
2. **HensuFactory Pattern** - Single entry point for all core infrastructure
3. **Shared Serialization** - `hensu-serialization` provides consistent JSON format for CLI and server
4. **Build-Then-Push** - `hensu build` compiles DSL to JSON; `hensu push` sends compiled JSON to server
5. **MCP-Only Server** - Server never executes locally, only sends MCP requests
6. **Flexible Planning** - Static (predefined) or Dynamic (LLM-generated)
7. **Multi-Tenancy** - Scoped Values for safe context propagation
8. **Storage Separation** - Persistence is server-specific, core is stateless
9. **API Separation** - Workflow definitions and executions are distinct resources
10. **Observability** - Plan events for debugging and monitoring
11. **Human Review** - At both plan and execution levels
