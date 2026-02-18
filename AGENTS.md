# AGENTS.md: Hensu™ Project Operational Manual

- Project Status: Pre-Beta
- Lead Developer: @alxsuv
- Standard: AGENTS.md v1.2 (2026)

This file is the Single Source of Truth for all AI coding agents (Claude, Cursor, etc.). You MUST read this before
proposing changes or executing commands.

---

## Documentation

| Document                                                 | Description                                    |
|----------------------------------------------------------|------------------------------------------------|
| [Core Developer Guide](docs/developer-guide-core.md)     | API usage, adapters, extension points, testing |
| [DSL Reference](docs/dsl-reference.md)                   | Complete Kotlin DSL syntax and examples        |
| [Javadoc Guide](docs/javadoc-guide.md)                   | Documentation standards                        |
| [Unified Architecture](docs/unified-architecture.md)     | Unified Architecture Vision                    |
| [Server Developer Guide](docs/developer-guide-server.md) | Server development patterns                    |

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew hensu-core:build
./gradlew hensu-serialization:build
./gradlew hensu-cli:build
./gradlew hensu-server:build

# Run all tests
./gradlew test

# Run tests for specific module
./gradlew hensu-core:test
./gradlew hensu-serialization:test
./gradlew hensu-server:test

# Run a single test class
./gradlew hensu-core:test --tests "RubricEngineTest"

# Run CLI in Quarkus dev mode
./gradlew hensu-cli:quarkusDev

# Run server in Quarkus dev mode
./gradlew hensu-server:quarkusDev
```

## Architecture Overview

Hensu is a modular AI workflow engine built on Java 25 with Kotlin DSL support.

Key features:

- Declarative Workflow Configuration - Define workflows declaratively with an intuitive Kotlin DSL
- Extensible Node System - Create custom nodes to extend workflow capabilities when needed
- Complex Workflows - Undirected flows, loops, forks, parallel execution, consensus-based decisions
- Rubric-Driven Quality Gates - Evaluate outputs against defined criteria with score-based routing
- Human Review Integration - Optional or required review at any workflow step
- Multi-Provider Support - Claude, GPT, Gemini, DeepSeek via pluggable adapters
- Pause / Resume - Workflows can pause at checkpoints and resume (potentially on a different server instance)
- Sub-Workflows - Hierarchical composition via `SubWorkflowNode` with input/output mapping
- Time-Travel Debugging - Execution history with backtracking support
- Zero Lock-In - Self-hosted, pure code, no proprietary formats

The core module design principle is **zero external dependencies** — all AI provider integrations happen through the
`AgentProvider` interface, wired explicitly via `HensuFactory.builder().agentProviders(...)`.

### Module Structure

```
hensu-core                    # Core workflow engine (pure Java, zero external deps)
hensu-dsl                     # Kotlin DSL for workflow definitions
hensu-serialization           # Jackson-based JSON serialization for workflow types (Node, TransitionRule, Action)
hensu-cli                     # Quarkus-based CLI (PicoCLI) - compiles DSL, executes locally
hensu-server                  # Quarkus-based server (native image) - receives JSON, executes via MCP
hensu-langchain4j-adapter     # LangChain4j integration (Claude, GPT, Gemini, DeepSeek)
```

**Dependency flow**: `cli → dsl → core`, `cli → serialization → core`, `server → serialization → core`,
`langchain4j-adapter → core`

### Key Components

**Entry Point**: `HensuFactory.builder()...build()` bootstraps everything:

- `HensuEnvironment` - Container holding all core components (NEVER construct components directly)
- `AgentFactory` - Creates agents from explicitly-wired providers
- `AgentRegistry` / `DefaultAgentRegistry` - Manages agent instances
- `WorkflowExecutor` - Executes workflow graphs
- `NodeExecutorRegistry` - Registry for node type executors
- `RubricEngine` - Evaluates output quality (rubrics embedded in workflow JSON)
- `TemplateResolver` - Resolves `{placeholder}` syntax in prompts
- `ReviewHandler` - Handles human review checkpoints (optional)
- `ActionExecutor` - Executes workflow actions (CLI: local bash, Server: MCP-only)
- `WorkflowRepository` - Tenant-scoped storage for workflow definitions (defaults to in-memory; JDBC impl in server)
- `WorkflowStateRepository` - Tenant-scoped storage for execution state snapshots (defaults to in-memory; JDBC impl in server)
- `ExecutionListener` - Lifecycle callbacks including `onCheckpoint(HensuState)` for inter-node persistence
- `ProcessorPipeline` - Orchestrates pre/post node execution processor chains

**Execution Lifecycle**: The `WorkflowExecutor` processes each node through a strict PRE-EXECUTE-POST pipeline.
Post-execution logic is handled by a chain of `PostNodeExecutionProcessor`s in fixed order: output extraction → history
recording → human review → rubric evaluation → transition resolution. Any processor can short-circuit the pipeline by
returning a terminal `ExecutionResult`. This design isolates cross-cutting concerns (e.g., adding a new rubric) from
the core agent execution logic.

**Server-Specific Components** (in `hensu-server`):

- `HensuEnvironmentProducer` - CDI producer using HensuFactory.builder(); conditionally wires JDBC or in-memory repos
- `ServerActionExecutor` - MCP-only action executor (rejects local execution)
- `WorkflowResource` - REST API for workflow definitions (push/pull/delete/list)
- `ExecutionResource` - REST API for execution runtime (start/resume/status/plan)
- `JdbcWorkflowRepository` - PostgreSQL-backed workflow storage (plain class, not CDI bean)
- `JdbcWorkflowStateRepository` - PostgreSQL-backed execution state storage (plain class, not CDI bean)

**AI Provider Interface**:

- `AgentProvider` interface in `io.hensu.core.agent`
- Providers wired explicitly via `HensuFactory.builder().agentProviders(...)` — no classpath scanning, GraalVM-safe
- Priority system: higher `getPriority()` wins when multiple providers support same model
- `StubAgentProvider` (priority 1000) always auto-included by `build()` for testing

### Data Model

**Workflow Structure** (immutable, builder pattern):

```
Workflow
├── agents: Map<String, AgentConfig>
├── rubrics: Map<String, String>
├── nodes: Map<String, Node>
└── startNode: String
```

**Node Types**:

- `StandardNode` - Regular step with agent execution and transitions
- `LoopNode` - Iterative execution with break conditions
- `ParallelNode` - Concurrent execution with consensus
- `ForkNode` - Spawn parallel execution paths
- `JoinNode` - Await and merge forked paths
- `GenericNode` - Custom execution logic via registered handlers
- `ActionNode` - Execute commands mid-workflow (git, deploy, notify)
- `EndNode` - Workflow termination (SUCCESS/FAILURE/CANCELLED)
- `SubWorkflowNode` - Nested workflows

**Transitions** (`TransitionRule` interface):

- `SuccessTransition` / `FailureTransition` - Based on execution result
- `ScoreTransition` - Conditional on rubric score (e.g., score >= 80 → approve)
- `AlwaysTransition` - Unconditional

### State Model

Execution state flows through three types:

- `HensuState` — Mutable runtime state during execution. Holds `executionId`, `workflowId`, `currentNode`, `context`
  (Map), and `ExecutionHistory`. Created by `WorkflowExecutor` at execution start; mutated during node transitions.
- `HensuSnapshot` — Immutable checkpoint record. Created from `HensuState.toSnapshot()` at pause points and completion.
  Stored in `WorkflowStateRepository`. Contains `checkpointReason` ("paused", "completed", etc.).
- `ExecutionHistory` — Tracks executed steps and backtracks. Its `copy()` returns mutable copies (not `List.copyOf()`)
  so resumed executions can continue appending steps.

**Checkpoint lifecycle** (inter-node persistence for failover):

1. `WorkflowExecutor.executeLoop()` calls `listener.onCheckpoint(state)` before each non-end node
2. `WorkflowService` implements `ExecutionListener.onCheckpoint()` to save a `HensuSnapshot` with reason `"checkpoint"`
3. If server dies mid-execution → restart → `findPaused()` returns interrupted executions → resume from last checkpoint

**Pause / Resume lifecycle:**

1. Node returns `ResultStatus.PENDING` → `WorkflowExecutor` returns `ExecutionResult.Paused(state)`
2. `WorkflowService` saves snapshot with reason `"paused"`
3. Later: `WorkflowService.resumeExecution()` loads snapshot → restores `HensuState` → calls
   `WorkflowExecutor.executeFrom()` → saves final snapshot

**Key context keys** (set by `WorkflowService.startExecution()`):

- `_tenant_id` — tenant identifier, read by `SubWorkflowNodeExecutor` for loading child workflows
- `_execution_id` — ensures `WorkflowExecutor` uses the same ID the service layer tracks

### Patterns & Conventions

1. **Builder pattern** for all domain models: `Workflow.builder().id(...).build()`
2. **Constructor injection** - No @Autowired, explicit dependency wiring
3. **Sealed interfaces** for results: `ExecutionResult` → `Completed | Paused | Rejected | Failure | Success`
4. **Template resolution**: `{variable}` syntax in prompts, resolved via `SimpleTemplateResolver`
5. **@DslMarker** on Kotlin builders to prevent scope leakage

### Kotlin DSL

Workflows defined in `.kt` files parsed by `KotlinScriptParser`:

```kotlin
workflow("example") {
    agents {
        agent("writer") { model = Models.CLAUDE_SONNET_4_5 }
    }
    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = "Write about {topic}"
            onSuccess goto "end"
        }

        end("end")
    }
}
```

### Environment Variables

Credentials loaded via `HensuFactory.loadCredentialsFromEnvironment()`:

- `ANTHROPIC_API_KEY` - Claude models
- `OPENAI_API_KEY` - GPT models
- `GOOGLE_API_KEY` - Gemini models
- `DEEPSEEK_API_KEY` - DeepSeek models
- `OPENROUTER_API_KEY`, `AZURE_OPENAI_KEY`

### CLI Commands

```bash
# Local execution
./hensu run -d working-dir workflow.kt                    # Execute workflow locally
./hensu run -d working-dir workflow.kt -v                 # Execute with verbose output
./hensu validate -d working-dir workflow.kt               # Validate syntax
./hensu visualize -d working-dir workflow.kt              # Visualize as ASCII text
./hensu visualize -d working-dir workflow.kt --format mermaid  # Visualize as Mermaid diagram

# Build (compile DSL → JSON)
./hensu build workflow.kt -d working-dir                  # Compile to working-dir/build/{id}.json

# Server workflow management (terraform/kubectl pattern)
./hensu push <workflow-id> --server http://host:8080      # Push compiled JSON to server
./hensu pull <workflow-id>                                 # Pull workflow definition from server
./hensu delete <workflow-id>                               # Delete workflow from server
./hensu list                                              # List all workflows on server
```

**Build-then-push workflow**: `build` compiles Kotlin DSL → JSON in `{working-dir}/build/`. `push` reads the compiled
JSON by workflow ID (not the .kt file).

### Key Architectural Rules

1. **HensuFactory pattern**: ALWAYS use `HensuFactory.builder()` - never construct core components directly
2. **Client-side compilation**: CLI compiles Kotlin DSL → JSON; server receives pre-compiled JSON (no Kotlin compiler in
   native image)
3. **Build-then-push**: `hensu build` compiles to `{working-dir}/build/`; `hensu push` reads compiled JSON (no
   recompilation)
4. **Shared serialization**: Both CLI and server use `hensu-serialization` for JSON format —
   `WorkflowSerializer.createMapper()` is the single ObjectMapper factory
5. **Server MCP-only**: Server never executes bash commands locally, only sends MCP requests to external tools
6. **Storage in core**: Repository interfaces (`WorkflowRepository`, `WorkflowStateRepository`) and in-memory defaults
   live in `hensu-core`. JDBC implementations live in `hensu-server/persistence/` as plain classes (not CDI beans).
   `HensuEnvironmentProducer` conditionally creates JDBC repos when DataSource is available, otherwise falls back to
   in-memory. Server delegates from `HensuEnvironment` via `@Produces @Singleton` — never creates instances directly
7. **API separation**: Workflow definitions (`/api/v1/workflows`) and executions (`/api/v1/executions`) are distinct
   REST resources
8. **JWT authentication**: Server uses SmallRye JWT (`quarkus-smallrye-jwt`) for bearer token auth. Tenant identity
   is extracted from the `tenant_id` claim via `RequestTenantResolver`. CLI sends `Authorization: Bearer <token>`
   header via `--token` option or `hensu.server.token` config. In dev/test mode, auth is disabled and a default tenant
   is used (`hensu.tenant.default`). RSA keys are stored externally (e.g., `~/.hensu/`), never in the repository.

## Testing

- JUnit 5 + AssertJ + Mockito
- Stub mode for testing without API calls: `HENSU_STUB_ENABLED=true`
- Mock agents for unit testing
- Core module testable in complete isolation (no AI dependencies)

### Integration Tests (`hensu-server`)

**Integration tests** extend `IntegrationTestBase` and run under `@QuarkusTest` with `@TestProfile(InMemoryTestProfile.class)`.
The `inmem` profile disables PostgreSQL (no Docker required). The base class provides:

- `loadWorkflow("fixture.json")` — Loads a JSON fixture from `test/resources/workflows/`
- `registerStub("nodeId", "response")` — Registers a programmatic stub response
- `registerStub("scenario", "nodeId", "response")` — Registers a scenario-specific stub
- `pushAndExecute(workflow, context)` — Saves workflow to repository and executes via `WorkflowService`
- `pushAndExecuteWithMcp(workflow, context, endpoint)` — Same but within a `TenantContext` with MCP endpoint
- `resolveRubricPath("quality.md")` — Copies a classpath rubric to a temp file (required by `RubricParser`)

Per-test cleanup (`@BeforeEach`): clears `StubResponseRegistry`, deletes all tenant data via
`WorkflowStateRepository.deleteAllForTenant()` and `WorkflowRepository.deleteAllForTenant()` (execution states first
due to FK constraint).

**Test handlers** (`@ApplicationScoped` beans auto-discovered by Quarkus):

- `TestActionHandler` — `GenericNodeHandler` for `type="test-action"`, captures invocations
- `TestReviewHandler` — Configurable `ReviewHandler` returning approve/reject/backtrack
- `TestPauseHandler` — `GenericNodeHandler` for `type="pause"`, returns PENDING on first call, SUCCESS on second
- `TestValidatorHandler` — `GenericNodeHandler` for `type="test-validator"`, validates context keys

**Stub resolution order**: programmatic → `/stubs/{scenario}/{nodeId}.txt` → `/stubs/default/{nodeId}.txt` → echo
fallback.

**Repository tests** (`io.hensu.server.persistence`) use plain JUnit 5 + Testcontainers PostgreSQL (no Quarkus context).
`JdbcRepositoryTestBase` starts a PostgreSQL container, runs Flyway migrations, and provides a DataSource. Tests verify
CRUD operations, UPSERT semantics, FK constraints, tenant isolation, and serialization round-trips.

## Key Files to Understand

**Core:**

- `hensu-core/.../HensuFactory.java` - Bootstrap and environment creation
- `hensu-core/.../HensuEnvironment.java` - Container for all core components
- `hensu-core/.../agent/AgentFactory.java` - Creates agents from explicit providers
- `hensu-core/.../execution/WorkflowExecutor.java` - Main execution engine
- `hensu-core/.../execution/pipeline/ProcessorPipeline.java` - Pre/post processor orchestration
- `hensu-core/.../execution/pipeline/ProcessorContext.java` - Per-iteration context carrier
- `hensu-core/.../workflow/Workflow.java` - Core data model
- `hensu-core/.../rubric/RubricEngine.java` - Quality evaluation engine
- `hensu-core/.../tool/ToolRegistry.java` - Protocol-agnostic tool descriptors
- `hensu-core/.../plan/PlanExecutor.java` - Step-by-step plan execution
- `hensu-core/.../workflow/WorkflowRepository.java` - Tenant-scoped workflow storage interface
- `hensu-core/.../state/WorkflowStateRepository.java` - Tenant-scoped execution state storage interface

**DSL:**

- `hensu-dsl/.../dsl/HensuDSL.kt` - DSL entry point (`workflow()` function)
- `hensu-dsl/.../dsl/parsers/KotlinScriptParser.kt` - Script compilation
- `hensu-dsl/.../dsl/builders/GraphBuilder.kt` - Graph DSL (node, action, end, etc.)

**Serialization:**

- `hensu-serialization/.../WorkflowSerializer.java` - Entry point: `toJson()`, `fromJson()`, `createMapper()`
- `hensu-serialization/.../HensuJacksonModule.java` - Jackson module registering all custom ser/deser
- `hensu-serialization/.../NodeSerializer.java` / `NodeDeserializer.java` - Node type hierarchy
- `hensu-serialization/.../TransitionRuleSerializer.java` / `TransitionRuleDeserializer.java` - Transition rules
- `hensu-serialization/.../ActionSerializer.java` / `ActionDeserializer.java` - Action types
- `hensu-serialization/.../mixin/` - Jackson mixins for builder-based deserialization (Workflow, AgentConfig)

**Server:**

- `hensu-server/.../config/HensuEnvironmentProducer.java` - CDI producer (HensuFactory → HensuEnvironment); conditional JDBC/in-memory wiring
- `hensu-server/.../config/ServerConfiguration.java` - CDI delegation + server beans (ObjectMapper via
  `WorkflowSerializer.createMapper()`)
- `hensu-server/.../action/ServerActionExecutor.java` - MCP-only action executor
- `hensu-server/.../api/WorkflowResource.java` - Workflow definition management REST API
- `hensu-server/.../api/ExecutionResource.java` - Execution runtime REST API
- `hensu-server/.../persistence/JdbcWorkflowRepository.java` - PostgreSQL workflow storage (plain JDBC, JSONB)
- `hensu-server/.../persistence/JdbcWorkflowStateRepository.java` - PostgreSQL execution state storage (plain JDBC, JSONB)
- `hensu-server/src/main/resources/db/migration/V1__create_persistence_tables.sql` - Flyway schema migration

**CLI:**

- `hensu-cli/.../commands/HensuCLI.java` - Top-level command registration
- `hensu-cli/.../commands/WorkflowBuildCommand.java` - Compile DSL → JSON (`hensu build`)
- `hensu-cli/.../commands/WorkflowPushCommand.java` - Push compiled JSON to server (`hensu push`)
- `hensu-cli/.../commands/ServerCommand.java` - Base class for server-interacting commands (HTTP client,
  --server/--token options, JWT bearer authentication)

**Examples:**

- `working-dir/workflows/*.kt` - Example workflows
