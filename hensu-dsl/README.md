# Hensu™ DSL

Kotlin DSL for defining AI agent workflows with type-safe builders.

## Overview

The `hensu-dsl` module provides:

- **Kotlin DSL** - Type-safe workflow definitions with `workflow { }` builder syntax
- **Kotlin Script Parser** - Compiles `.kt` workflow files at runtime via embedded Kotlin compiler
- **Simple Runner** - Standalone workflow execution for development and testing
- **Working Directory** - Convention-based file resolution for rubrics and resources

## Quick Start

### Define a Workflow

```kotlin
fun myWorkflow() = workflow("ContentPipeline") {
    agents {
        agent("writer") {
            role = "Content Writer"
            model = Models.CLAUDE_SONNET_4_5
        }
        agent("reviewer") {
            role = "Quality Reviewer"
            model = Models.GPT_4O
        }
    }

    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = "Write a short article about {topic}"
            onSuccess goto "review"
        }

        node("review") {
            agent = "reviewer"
            prompt = "Review this article: {write}"
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "end_success"
                whenScore lessThan 80.0 goto "write"
            }
        }

        end("end_success")
    }
}
```

### Run Directly

```bash
# Via Gradle task
./gradlew hensu-dsl:runWorkflow -Pworkflow=path/to/workflow.kt -Pargs="--stub --verbose"

# Via CLI (recommended)
./hensu run workflow.kt -d working-dir
```

### Compile to JSON

The DSL compiles workflows to JSON for server deployment:

```bash
# Compile DSL → JSON
./hensu build workflow.kt -d working-dir

# Push compiled JSON to server
./hensu push my-workflow --server http://localhost:8080
```

## DSL Reference

See [DSL Reference](../docs/dsl-reference.md) for the complete syntax reference including:

- Workflow structure and configuration
- Agent definitions and model constants
- Node types (standard, parallel, fork/join, action, generic, end)
- Transition rules (success, failure, score-based, approval, consensus)
- State variables (`writes()` + `{placeholder}` syntax)
- State schema (`state { }` block with typed `input`/`variable` declarations and load-time validation)
- Approval routing (`onApproval goto` / `onRejection goto`)
- Plan failure routing (`onPlanFailure goto`)
- Rubric-driven quality gates
- Human review configuration
- Planning (static `plan { }` and dynamic `planning { }`)
- External prompt files (`.md` from `prompts/` directory)

## Module Structure

```
hensu-dsl/src/main/kotlin/io/hensu/dsl/
├── HensuDSL.kt                   # Top-level `workflow { }` entry point
├── WorkingDirectory.kt            # Convention-based file resolution
├── builders/
│   ├── WorkflowBuilder.kt        # Root workflow builder
│   ├── WorkflowConfigBuilder.kt  # Workflow-level configuration
│   ├── GraphBuilder.kt           # Graph definition (`start at`, nodes, edges)
│   ├── AgentBuilder.kt           # Agent configuration builder
│   ├── StandardNodeBuilder.kt    # Standard LLM node builder
│   ├── ParallelNodeBuilder.kt    # Parallel execution builder
│   ├── ForkJoinBuilders.kt       # Fork/join node builders
│   ├── ActionNodeBuilder.kt      # Action node builder
│   ├── GenericNodeBuilder.kt     # Custom node type builder
│   ├── EndNodeBuilder.kt         # Terminal node builder
│   ├── BaseNodeBuilder.kt        # Shared node builder base
│   ├── StateSchemaBuilder.kt     # Typed state schema builder (`state { }` block)
│   ├── TransitionBuilder.kt      # Transition rule builders
│   ├── ScoreTransitionBuilder.kt # Score-based routing
│   ├── ScoreConditionBuilder.kt  # Score condition expressions
│   ├── RubricBuilder.kt          # Quality gate definition
│   ├── RetryBuilder.kt           # Retry configuration
│   ├── ReviewConfigBuilder.kt    # Human review settings
│   ├── PlanBuilder.kt            # Agentic planning config
│   ├── PlanningConfigBuilder.kt  # Planning constraints
│   ├── ObservabilityBuilder.kt   # Logging and tracing config
│   ├── Models.kt                 # Model constants (CLAUDE_SONNET_4_5, GPT_4O, etc.)
│   └── DslMarkers.kt             # DSL scope markers
├── extensions/
│   └── DslHelpers.kt             # Extension functions for DSL sugar
├── parsers/
│   └── KotlinScriptParser.kt     # .kt file → Workflow compilation
├── runners/
│   └── SimpleRunner.kt           # Standalone execution entry point
└── internal/
    └── DSLContext.kt             # Internal DSL state management
```

## Key Concepts

### Client-Side Compilation

The DSL module contains the Kotlin compiler — this runs on the **client** (CLI), never on the server. The compilation
flow:

```
workflow.kt → KotlinScriptParser → Workflow object → JSON (via hensu-serialization) → Server
```

The server receives pre-compiled JSON and has no Kotlin compiler dependency.

### Working Directory

Workflows reference external files (rubrics, resources) relative to a working directory:

```bash
working-dir/
├── workflows/
│   └── my-workflow.kt
├── prompts/
│   └── agent-prompt.md
├── rubrics/
│   └── content-quality.md
└── build/
    └── my-workflow.json    # Compiled output
```

### Available Models

The `Models` object provides constants for supported AI models:

| Constant                   | Model                        |
|----------------------------|------------------------------|
| `Models.CLAUDE_SONNET_4_5` | `claude-sonnet-4-5-20250929` |
| `Models.CLAUDE_SONNET_4`   | `claude-sonnet-4-20250514`   |
| `Models.GPT_4O`            | `gpt-4o`                     |
| `Models.GPT_4O_MINI`       | `gpt-4o-mini`                |
| `Models.GEMINI_2_5_FLASH`  | `gemini-2.5-flash`           |
| `Models.DEEPSEEK_CHAT`     | `deepseek-chat`              |

## Documentation

| Document                                  | Description                             |
|-------------------------------------------|-----------------------------------------|
| [DSL Reference](../docs/dsl-reference.md) | Complete Kotlin DSL syntax and examples |

## Dependencies

- **hensu-core** - Core workflow data model and execution engine
- **Kotlin Stdlib + Reflect** - Kotlin standard library
- **Kotlin Scripting** - Runtime `.kt` file compilation (2.3.10)
- **Kotlin Compiler Embeddable** - Embedded Kotlin compiler
