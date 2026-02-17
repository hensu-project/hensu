# AGENTS.md

This file provides project-specific instructions for AI coding agents.

## Documentation

| Document                                                        | Description                                              |
|-----------------------------------------------------------------|----------------------------------------------------------|
| [Core Developer Guide](docs/core-developer-guide.md)            | API usage, adapters, extension points, testing           |
| [DSL Reference](docs/dsl-reference.md)                          | Complete Kotlin DSL syntax and examples                  |
| [Javadoc Guide](docs/javadoc-guide.md)                          | Documentation standards                                  |
| [Unified Architecture](docs/unified-architecture.md)            | Unified Architecture Vision                              |
| [Core README](hensu-core/README.md)                             | Core module architecture and components                  |
| [DSL README](hensu-dsl/README.md)                               | DSL module overview and quick start                      |
| [CLI README](hensu-cli/README.md)                               | CLI commands and build-then-push workflow                |
| [Server README](hensu-server/README.md)                         | Server module overview and quick start                   |
| [Server Developer Guide](docs/server-developer-guide.md)        | Server development patterns                              |
| [GraalVM Native Image](#graalvm-native-image)                   | Set of rules to keep system native-image safe            |
| [Pommel - Semantic Code Search](#pommel---semantic-code-search) | Context Retrieval Strategy: Semantic & Heuristic Ruleset |

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

**Server-Specific Components** (in `hensu-server`):

- `HensuEnvironmentProducer` - CDI producer using HensuFactory.builder()
- `ServerActionExecutor` - MCP-only action executor (rejects local execution)
- `WorkflowResource` - REST API for workflow definitions (push/pull/delete/list)
- `ExecutionResource` - REST API for execution runtime (start/resume/status/plan)
- `WorkflowRepository` / `WorkflowStateRepository` - Server-specific persistence

**AI Provider Interface**:

- `AgentProvider` interface in `io.hensu.core.agent.spi`
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

### Patterns & Conventions

1. **Builder pattern** for all domain models: `Workflow.builder().id(...).build()`
2. **Constructor injection** - No @Autowired, explicit dependency wiring
3. **Sealed interfaces** for results: `ExecutionResult` → `Completed | Rejected`
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
6. **Storage separation**: Core has NO persistence interfaces; storage is server-specific (
   `io.hensu.server.persistence`)
7. **API separation**: Workflow definitions (`/api/v1/workflows`) and executions (`/api/v1/executions`) are distinct
   REST resources

## Testing

- JUnit 5 + AssertJ + Mockito
- Stub mode for testing without API calls: `HENSU_STUB_ENABLED=true`
- Mock agents for unit testing
- Core module testable in complete isolation (no AI dependencies)

## Key Files to Understand

**Core:**

- `hensu-core/.../HensuFactory.java` - Bootstrap and environment creation
- `hensu-core/.../HensuEnvironment.java` - Container for all core components
- `hensu-core/.../agent/AgentFactory.java` - Creates agents from explicit providers
- `hensu-core/.../execution/WorkflowExecutor.java` - Main execution engine
- `hensu-core/.../workflow/Workflow.java` - Core data model
- `hensu-core/.../rubric/RubricEngine.java` - Quality evaluation engine
- `hensu-core/.../tool/ToolRegistry.java` - Protocol-agnostic tool descriptors
- `hensu-core/.../plan/PlanExecutor.java` - Step-by-step plan execution

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

- `hensu-server/.../config/HensuEnvironmentProducer.java` - CDI producer (HensuFactory → HensuEnvironment)
- `hensu-server/.../config/ServerConfiguration.java` - CDI delegation + server beans (ObjectMapper via
  `WorkflowSerializer.createMapper()`)
- `hensu-server/.../action/ServerActionExecutor.java` - MCP-only action executor
- `hensu-server/.../api/WorkflowResource.java` - Workflow definition management REST API
- `hensu-server/.../api/ExecutionResource.java` - Execution runtime REST API
- `hensu-server/.../persistence/WorkflowRepository.java` - Workflow storage interface
- `hensu-server/.../persistence/WorkflowStateRepository.java` - Execution state storage interface

**CLI:**

- `hensu-cli/.../commands/HensuCLI.java` - Top-level command registration
- `hensu-cli/.../commands/WorkflowBuildCommand.java` - Compile DSL → JSON (`hensu build`)
- `hensu-cli/.../commands/WorkflowPushCommand.java` - Push compiled JSON to server (`hensu push`)
- `hensu-cli/.../commands/ServerCommand.java` - Base class for server-interacting commands (HTTP client,
  --server/--tenant options)

**Examples:**

- `working-dir/workflows/*.kt` - Example workflows

## GraalVM Native Image

The server is deployed as a GraalVM native image via Quarkus. All server code — and any dependency it pulls in — must be
native-image safe. See
the [hensu-core Developer Guide](../../hensu-core/docs/developer-guide.md#graalvm-native-image-constraints) for the
foundational rules (no reflection, no classpath scanning, no dynamic proxies, no runtime bytecode generation). This
section covers **server-specific** concerns.

### How Quarkus Changes the Picture

Quarkus performs heavy build-time processing that relaxes some raw GraalVM constraints:

| Feature                            | Raw GraalVM                | With Quarkus                                               |
|------------------------------------|----------------------------|------------------------------------------------------------|
| CDI injection (`@Inject`)          | Requires reflection config | Works — Quarkus resolves beans at build time (ArC)         |
| `@ConfigProperty`                  | Requires reflection config | Works — processed at build time                            |
| JAX-RS resources (`@Path`, `@GET`) | Requires reflection config | Works — REST layer is build-time wired                     |
| Jackson `@JsonProperty` on DTOs    | Requires reflection config | Works — `quarkus-jackson` registers metadata               |
| `ServiceLoader`                    | Fails at runtime           | Works — Quarkus scans `META-INF/services` at build time    |
| LangChain4j AI services            | Requires reflection config | Works — `quarkus-langchain4j` extensions register metadata |

**Key insight**: Within Quarkus-managed code, standard annotations and CDI work normally. The constraints only bite when
you introduce code that Quarkus doesn't know about — custom reflection, third-party libraries without Quarkus
extensions, or `hensu-core` internals that bypass the framework.

### Adding New Dependencies

When adding a new library to `hensu-server`:

1. **Check if a Quarkus extension exists.** Search [extensions catalog](https://quarkus.io/extensions/) first.
   Extensions provide build-time metadata, so you get native-image support automatically.

2. **If an extension exists**, add the Quarkus extension (not the raw library):
   ```kotlin
   // build.gradle.kts
   implementation("io.quarkus:quarkus-my-library")  // Quarkus extension
   // NOT: implementation("org.example:my-library")  // raw library
   ```

3. **If no extension exists**, you must verify native-image compatibility:
    - Run `./gradlew hensu-server:build -Dquarkus.native.enabled=true`
    - Test the binary: `./hensu-server/build/hensu-server-*-runner`
    - If it fails with `ClassNotFoundException` or `NoSuchMethodException`, add reflection configuration:
      ```json
      // src/main/resources/reflect-config.json
      [
        {
          "name": "com.example.SomeClass",
          "allDeclaredConstructors": true,
          "allPublicMethods": true
        }
      ]
      ```

4. **Pin the version to match Quarkus BOM.** If the library is managed by the Quarkus BOM (e.g., Jackson, Vert.x), do
   not override the version. Mismatched versions cause subtle native-image failures.

### CDI Producers and Native Image

CDI producers in `ServerConfiguration` are native-image safe because Quarkus processes them at build time. Follow these
patterns:

```java
// SAFE — Quarkus resolves this at build time
@Produces
@Singleton
public WorkflowExecutor workflowExecutor(HensuEnvironment env) {
    return env.getWorkflowExecutor();
}

// SAFE — concrete instantiation
@Produces
@Singleton
public WorkflowRepository workflowRepository() {
    return new InMemoryWorkflowRepository();
}

// UNSAFE — dynamic class loading in a producer
@Produces
@Singleton
public Object dynamicBean() {
    return Class.forName(config.getClassName()).newInstance();  // fails in native
}
```

### Server-Specific Notes

**Mutiny reactive types are safe.** `Uni`, `Multi`, `BroadcastProcessor` all work in native image — Quarkus handles
their registration.

**MCP JSON-RPC uses explicit Jackson.** The `JsonRpc` class uses `ObjectMapper` directly with `readTree`/
`writeValueAsString` — no reflection-based deserialization. This is intentionally safe for native image.

### Verifying Native Image Compatibility

```bash
# Full native build (slow — run before releases, not on every change)
./gradlew hensu-server:build -Dquarkus.native.enabled=true

# Quick JVM-mode test (catches most issues except native-specific ones)
./gradlew hensu-server:test

# Native integration tests
./gradlew hensu-server:test -Dquarkus.test.native-image-profile=true
```

### Quick Reference (Server-Specific)

| Pattern                                             | Safe  | Notes                                     |
|-----------------------------------------------------|-------|-------------------------------------------|
| `@Inject` / `@Produces`                             | Yes   | Quarkus ArC — build-time CDI              |
| `@ConfigProperty`                                   | Yes   | Build-time processed                      |
| Quarkus extensions                                  | Yes   | Provide native metadata                   |
| Raw third-party libs                                | Maybe | Need `reflect-config.json` if reflective  |
| `ObjectMapper.readTree()`                           | Yes   | No reflection — tree-model parsing        |
| `new ObjectMapper().readValue(json, MyClass.class)` | Maybe | Needs registration unless Quarkus-managed |
| Mutiny `Uni`/`Multi`                                | Yes   | Quarkus-managed                           |

## Pommel - Semantic Code Search

### Overview

Pommel is a semantic code search tool designed as a first-line discovery method. It returns semantic matches with
approximately 18x fewer tokens than grep-based searching.

### When to Use

**Use `pm search` for:**

- Finding implementations
- Locating where specific functionality is handled
- Iterative code exploration
- General code lookups (recommended as first attempt)

**Use grep/file explorer instead for:**

- Verifying something does NOT exist
- Searching exact string literals and error messages
- Understanding architecture and code flow
- Needing full file context

### Search Syntax Examples

```bash
pm search "IPC handler for updates"
pm search "validation logic" --path src/shared
pm search "state management" --level function,method
pm search "error handling" --json --limit 5
pm search "authentication" --metrics
```

### Score Interpretation

- **> 0.7**: Strong match - use directly
- **0.5-0.7**: Moderate match - review snippet, may require additional reading
- **< 0.5**: Weak match - try different query or use grep

### Key Command Flags

- `--path <prefix>`: Scope results to specific directory
- `--level <types>`: Filter by code structure (file, class, function, method, block)
- `--limit N`: Limit number of results (default: 10)
- `--verbose`: Display match reasoning
- `--json`: Structured output format

### Other Commands

- `pm status`: Check daemon status and index statistics
- `pm reindex`: Force a full reindex of the codebase