# Hensu - The Open Source Agentic Workflow Engine

Self-hosted. Developer-friendly. Enterprise-ready.
Build complex AI agent workflows with code.
No vendor lock-in. No "Contact Sales."

## Key Features

- **Complex Workflows** - Undirected flows, loops, forks, parallel execution, consensus-based decisions
- **Rubric-Driven Quality Gates** - Evaluate outputs against defined criteria with score-based routing
- **Human Review Integration** - Optional or required review at any workflow step
- **Multi-Provider Support** - Claude, GPT, Gemini, DeepSeek via pluggable adapters
- **Time-Travel Debugging** - Execution history with backtracking support
- **Zero Lock-In** - Self-hosted, pure code, no proprietary formats

## Quick Start

### Prerequisites

- Java 25+
- API key for your preferred LLM provider (Anthropic, OpenAI, DeepSeek or Google)

### Run Your First Workflow

```bash
# Clone and build
git clone https://github.com/alxsuv/hensu.git
cd hensu
./gradlew build -x test

# Set your API key
export ANTHROPIC_API_KEY="sk-ant-..."
# or: export OPENAI_API_KEY="sk-..."
# or: export GOOGLE_API_KEY="..."
# or: export DEEPSEEK_API_KEY="sk-..."

# Run a workflow
./hensu run -d working-dir georgia-discovery.kt
```

### CLI Commands

```bash
./hensu run -d working-dir workflow.kt                         # Execute a workflow
./hensu run -d working-dir workflow.kt -v                      # Execute with verbose output
./hensu validate -d working-dir workflow.kt                    # Validate workflow syntax
./hensu visualize -d working-dir workflow.kt                   # Visualize as ASCII text
./hensu visualize -d working-dir workflow.kt --format=mermaid  # Visualize as Mermaid diagram
```

### Example Workflow

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
                whenScore lessThan 80.0 goto "write"  // Loop back for revision
            }
        }

        end("end_success")
    }
}
```

## Documentation

| Document | Description |
|----------|-------------|
| [DSL Reference](docs/dsl-reference.md) | Complete Kotlin DSL syntax and examples |
| [Developer Guide](docs/developer-guide.md) | Architecture, API usage, adapter development |
| [Javadoc Guide](docs/javadoc-guide.md) | Documentation standards |
| [CLAUDE.md](CLAUDE.md) | Claude Code project instructions |
| [Pommel](https://github.com/dbinky/Pommel) | Local-first semantic code search for AI coding agents (optional) |

## Architecture

Hensu uses a modular adapter pattern with zero AI dependencies in the core:

```
hensu-core                    # Core workflow engine (pure Java, no AI deps)
hensu-cli                     # Quarkus-based CLI
hensu-langchain4j-adapter     # LangChain4j integration (Claude, GPT, Gemini)
```

Providers are discovered automatically via Java's ServiceLoader (SPI).

## Stub Mode (Development/Testing)

Run workflows without consuming API tokens:

```bash
export HENSU_STUB_ENABLED=true
./hensu run -d working-dir workflow.kt -v
```

## Build Commands

```bash
./gradlew build           # Build all modules
./gradlew test            # Run all tests
./gradlew hensu-cli:quarkusDev  # Run in dev mode
```

Or use Make:

```bash
make build    # Build all
make test     # Run tests
make run      # Run default workflow
make dev      # Dev mode
```

## License

[Your License Here]

## Contributing

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

See [Developer Guide](docs/developer-guide.md) for architecture details and adapter development.

## Using with Claude Code

This repository includes a `CLAUDE.md` file with project-specific instructions for [Claude Code](https://claude.ai/code). Claude Code automatically reads this file to understand the codebase structure, build commands, and coding conventions.

### Using AGENTS.md Instead

If you prefer to use `AGENTS.md` (for compatibility with other AI coding tools or team conventions), you can override Claude Code's default behavior:

**Option 1: Global setting (all projects)**

Add to your `~/.claude/settings.json`:

```json
{
  "agentInstructionsFile": "AGENTS.md"
}
```

**Option 2: Project-specific setting**

Create `.claude/settings.json` in your project root:

```json
{
  "agentInstructionsFile": "AGENTS.md"
}
```

Then rename or copy `CLAUDE.md` to `AGENTS.md`:

```bash
mv CLAUDE.md AGENTS.md
# or
cp CLAUDE.md AGENTS.md
```

Claude Code will now read `AGENTS.md` instead of `CLAUDE.md` for project instructions.