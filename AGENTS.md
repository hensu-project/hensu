# AGENTS.md

This file provides project-specific instructions for AI coding agents.

## Documentation

| Document | Description |
|----------|-------------|
| [DSL Reference](docs/dsl-reference.md) | Complete Kotlin DSL syntax and examples |
| [Developer Guide](docs/developer-guide.md) | Architecture, API usage, engine extensions |
| [Javadoc Guide](docs/javadoc-guide.md) | Documentation standards |

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew hensu-core:build
./gradlew hensu-cli:build

# Run all tests
./gradlew test

# Run tests for specific module
./gradlew hensu-core:test

# Run a single test class
./gradlew hensu-core:test --tests "RubricEngineTest"

# Run CLI in Quarkus dev mode
./gradlew hensu-cli:quarkusDev
```

## Architecture Overview

Hensu is a modular AI workflow engine built on Java 25 with Kotlin DSL support.

Key features:
- Complex workflow creation with undirected flows, loops, forks, parallel execution
- Intelligent phase transitions with score-based routing
- Phase-based backtracking to any available previous step
- Rubric-driven quality gates
- Human or automatic review at phase boundaries
- Consensus-based parallel step execution
- Time-travel debugging with rubric-triggered auto-correction

The core module design principle is **zero external dependencies** - all AI provider integrations happen through Java's ServiceLoader (SPI) pattern.

### Module Structure

```
hensu-core                    # Core workflow engine + Kotlin DSL (pure Java/Kotlin, no AI deps)
hensu-cli                     # Quarkus-based CLI (PicoCLI)
hensu-langchain4j-adapter     # LangChain4j integration (Claude, GPT, Gemini, DeepSeek)
```

**Dependency flow**: `cli → core ← langchain4j-adapter`

### Key Components

**Entry Point**: `HensuFactory.createEnvironment()` bootstraps everything:
- `HensuEnvironment` - Container holding all core components
- `AgentFactory` - Discovers AI providers via ServiceLoader
- `AgentRegistry` / `DefaultAgentRegistry` - Manages agent instances
- `WorkflowExecutor` - Executes workflow graphs
- `NodeExecutorRegistry` - Registry for node type executors
- `RubricEngine` - Evaluates output quality using RubricRepository and RubricEvaluator
- `TemplateResolver` - Resolves `{placeholder}` syntax in prompts
- `ReviewHandler` - Handles human review checkpoints (optional)
- `ActionExecutor` - Executes workflow actions (optional)

**AI Provider SPI**:
- `AgentProvider` interface in `io.hensu.core.agent.spi`
- Implementations discovered via `META-INF/services/io.hensu.core.agent.spi.AgentProvider`
- Priority system: higher `getPriority()` wins when multiple providers support same model

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
./hensu run -d working-dir workflow.kt                    # Execute workflow
./hensu run -d working-dir workflow.kt -v                 # Execute with verbose output
./hensu validate -d working-dir workflow.kt               # Validate syntax
./hensu visualize -d working-dir workflow.kt              # Visualize as ASCII text
./hensu visualize -d working-dir workflow.kt --format mermaid  # Visualize as Mermaid diagram
```

## Testing

- JUnit 5 + AssertJ + Mockito
- Stub mode for testing without API calls: `HENSU_STUB_ENABLED=true`
- Mock agents for unit testing
- Core module testable in complete isolation (no AI dependencies)

## Key Files to Understand

- `hensu-core/.../HensuFactory.java` - Bootstrap and environment creation
- `hensu-core/.../agent/AgentFactory.java` - ServiceLoader discovery
- `hensu-core/.../execution/WorkflowExecutor.java` - Main execution engine
- `hensu-core/.../workflow/Workflow.java` - Core data model
- `hensu-core/.../dsl/HensuDSL.kt` - DSL entry point (`workflow()` function)
- `hensu-core/.../dsl/parsers/KotlinScriptParser.kt` - Script compilation
- `hensu-core/.../dsl/builders/GraphBuilder.kt` - Graph DSL (node, action, end, etc.)
- `working-dir/workflows/*.kt` - Example workflows

## Pommel - Semantic Code Search

### Overview
Pommel is a semantic code search tool designed as a first-line discovery method. It returns semantic matches with approximately 18x fewer tokens than grep-based searching.

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