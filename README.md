<div align="center">

# Hensu

### The high-performance orchestration engine for declarative AI workflows.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://jdk.java.net/)
[![Build](https://img.shields.io/badge/Build-Native%20Image-success)](https://graalvm.org/)
[![Protocol](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)
[![Status](https://img.shields.io/badge/Status-Alpha-red)]()

**Define. Build. Run.**<br>
Self-hosted. Developer-friendly. Zero lock-in.

[Get Started](#getting-started) • [Architecture](docs/unified-architecture.md) • [DSL Reference](docs/dsl-reference.md) • [Documentation](docs)

</div>

---

Most AI orchestration frameworks couple workflow logic to application code, making multi-agent systems
hard to version, test, and deploy independently. **Hensu** solves this with a Build-Then-Push architecture — analogous
to Terraform — that decouples the **Definition of Intelligence** from its **Runtime Execution**.

Workflows are authored and compiled locally via a **type-safe Kotlin DSL**, producing portable JSON definitions.
The underlying engine is a high-performance, **native-image server** designed for multi-tenant SaaS environments.
It leverages Java `ScopedValues` to ensure rigorous execution isolation while managing the complex orchestration
of LLMs, MCP-based tool calling, and human-in-the-loop gates.

## Key Capabilities

| Feature               | Description                                                                                                                                  |
|:----------------------|:---------------------------------------------------------------------------------------------------------------------------------------------|
| **Pure Java Core**    | Zero-dependency execution engine built on Java 25 virtual threads.                                                                           |
| **Type-Safe DSL**     | Describe, don't implement. Define complex agent behaviors using a type-safe, declarative syntax.                                             |
| **Non-Linear Flow**   | Support for loops, conditional branches, and jump-to-node logic, moving beyond simple sequential chains.                                     |
| **Sub-Workflows**     | Hierarchical composition via `SubWorkflowNode` with input/output mapping for reusable, modular workflow definitions.                         |
| **MCP Gateway**       | Provides seamless remote tool execution. Connects to any remote MCP server to run tools externally, keeping the engine core secure and lean. |
| **Multi-Tenancy**     | Rigorous isolation using Java `ScopedValues` for safe SaaS deployment.                                                                       |
| **Resilience**        | Distributed state architecture allows workflows to be paused, resumed, and migrated.                                                         |
| **Agentic Planning**  | Supports both static (pre-defined) and dynamic (LLM-generated) execution plans.                                                              |
| **Human in the Loop** | Integrated "Checkpoints" allowing manual approval or intervention before high-stakes node transitions.                                       |
| **Rubric Evaluation** | Automated quality gates that verify node outputs against defined criteria before allowing workflow progression.                              |

---

## The Hensu Stack

Hensu strictly separates **Development** (managed by you) from **Execution** (managed by the server).

### 1. The Toolchain (Compiler & CLI)

* **[hensu-dsl](hensu-dsl/README.md):** The Source. A Kotlin DSL that compiles `.kt` files into static, versioned
  **Workflow Definitions** (JSON).
* **[hensu-cli](hensu-cli/README.md):** The Manager. Acts as a package manager for your workflows. It compiles
  definitions and synchronizes them with the Server's persistence layer (`build`, `push`, `pull`, `delete`, `list`).

### 2. The Runtime (Server & Core)

* **[hensu-core](hensu-core/README.md):** The Engine. A pure Java library that hydrates static Definitions into
  executable graphs. It handles state transitions, rubric evaluation, and agent interactions.
* **[hensu-server](hensu-server/README.md):** The Platform. A stateless, multi-tenant server that executes workflows.
  Its core feature is the **SSE Split-Pipe** for MCP: a secure, bidirectional tunnel that allows the server to request
  tool execution on tenant clients without requiring open inbound ports. It also exposes REST APIs for triggering
  instances and standard SSE for event monitoring.

### 3. Shared Infrastructure

* **[hensu-serialization](hensu-serialization/README.md):** The Wire Format. Jackson mixins and serializers that
  convert between in-memory domain objects and their JSON representation. Used by both the CLI (to produce definitions)
  and the Server (to hydrate them).

---

## Architecture

The architecture follows a strict **Split-Pipe** model:

1. **Compilation:** The CLI compiles Kotlin DSL into a static **Workflow Definition** (JSON).
2. **Distribution:** The Definition is pushed to the Server's persistence layer.
3. **Execution:** The Server hydrates the Definition into an active instance. When a tool is needed, it uses the
   **Split-Pipe** transport:
    * **Downstream (SSE):** The Server pushes a JSON-RPC tool request to the connected tenant client.
    * **Upstream (HTTP):** The Client executes the tool locally and `POST`s the result back.

**No user code runs on the server.** The server is a pure orchestrator; all side effects happen on the client via MCP.

```
  Developer (local)                                    Hensu Runtime              External
 ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌─────────────────────┐    ┌──────────────┐
 │ Kotlin   │───>│  hensu   │───>│   JSON   │───>│  Hensu Server       │<──>│ LLMs (Claude │
 │ DSL      │    │  build   │    │   Def.   │    │  (GraalVM Native)   │    │ GPT, Gemini) │
 └──────────┘    └──────────┘    └──────────┘    │                     │    └──────────────┘
                                   hensu push    │  Core Engine        │    ┌──────────────┐
                                                 │  ├ State Manager    │<──>│ MCP Tool     │
                                                 │  ├ Rubric Evaluator │    │ Servers      │
                                                 │  └ Consensus Engine │    └──────────────┘
                                                 └─────────────────────┘
```

---

## Workflow Example

Define complex, self-correcting behaviors using the Kotlin DSL. This example shows a writer/reviewer loop with a quality
gate.

```kotlin
fun contentPipeline() = workflow("ContentPipeline") {
    description = "Research, write, and review content with parallel review"
    version = "1.0.0"

    agents {
        agent("researcher") {
            role = "Research Analyst"
            model = Models.GEMINI_2_5_FLASH
            temperature = 0.3
        }

        agent("writer") {
            role = "Content Writer"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.7
            maintainContext = true
        }

        agent("reviewer") {
            role = "Content Reviewer"
            model = Models.GPT_4O
            temperature = 0.5
        }
    }

    rubrics {
        rubric("content-quality", "content-quality.md")
    }

    graph {
        start at "research"

        node("research") {
            agent = "researcher"
            prompt = """
                Research {topic} and provide key facts.
                Output as JSON: {"fact1": "...", "fact2": "...", "fact3": "..."}
            """.trimIndent()

            outputParams = listOf("fact1", "fact2", "fact3")

            onSuccess goto "write"
            onFailure retry 2 otherwise "end_failure"
        }

        node("write") {
            agent = "writer"
            prompt = """
                Write an article about {topic} using these facts:
                - {fact1}
                - {fact2}
                - {fact3}
            """.trimIndent()

            onSuccess goto "parallel-review"
        }

        parallel("parallel-review") {
            branch("quality-review") {
                agent = "reviewer"
                prompt = "Review for quality: {write}"
            }
            branch("accuracy-review") {
                agent = "reviewer"
                prompt = "Review for accuracy: {write}"
            }

            consensus {
                strategy = ConsensusStrategy.MAJORITY_VOTE
                threshold = 0.5
            }

            onConsensus goto "end_success"
            onNoConsensus goto "write"
        }

        end("end_success", ExitStatus.SUCCESS)
        end("end_failure", ExitStatus.FAILURE)
    }
}
```

---

## Getting Started

### Prerequisites

- JDK 25+ (for building source)
- Docker (optional, for running the native server)
- API Keys (Anthropic, Gemini, OpenAI, etc.)

### 1. Installation

```shell
git clone https://github.com/hensu-project/hensu.git && cd hensu
./gradlew build -x test
```

### 2. Run the Server

```shell
java -jar hensu-server/build/quarkus-app/quarkus-run.jar
```

### 3. Build & Deploy a Workflow

```shell
# Create a new workflow file
echo 'fun flow() = workflow("hello") { ... }' > hello.kt

# Compile DSL to JSON
./hensu build hello.kt -d .

# Push to the local server
./hensu push hello --server http://localhost:8080
```

### 4. Execute

```shell
curl -X POST http://localhost:8080/api/v1/executions \
  -H "X-Tenant-ID: tenant-1" \
  -d '{"workflowId": "hello", "context": {"topic": "AI Agents"}}'
```

### Agent-Native Workflow

Hensu is pre-configured with architectural rules in `.claude/rules/` and `.cursor/rules/`. You do not need to create
custom instructions or CLAUDE.md files.

**Context Retrieval:**

* **Recommended:** Install **[Pommel](https://github.com/dbinky/Pommel)** for the highest accuracy. The agent will use
  it to understand the codebase semantically.
* **Optional:** If you do not install Pommel, the agent is configured to **automatically fall back** to standard tools
  (`grep`, `find`). No configuration is required; the agent will simply warn you about reduced context accuracy.

---

## Security Model

Hensu is designed for Zero-Trust environments. The server is a **pure orchestrator** — it never executes
user-supplied code.

| Principle              | Implementation                                                                                                |
|:-----------------------|:--------------------------------------------------------------------------------------------------------------|
| **No Local Execution** | The server has no shell, no `eval`, no script runner. All side effects route through MCP to tenant clients.   |
| **Tenant Isolation**   | Every execution runs inside a Java `ScopedValue` boundary. No data leaks between concurrent workflows.        |
| **No Inbound Ports**   | The Split-Pipe transport means tenant clients connect *outbound* via SSE. No firewall rules required.         |
| **Stateless Server**   | Workflow state is externalized to pluggable repositories. The server can be killed and restarted at any time. |
| **Native Binary**      | GraalVM native image eliminates classpath scanning, reflection, and dynamic class loading attack surfaces.    |

<div align="center">

Built with

Java 25 • Kotlin DSL • Quarkus • GraalVM Native Image • MCP Protocol

</div>
