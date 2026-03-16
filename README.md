<div align="center">
    <img src="assets/logo.png" alt="hensu logo" width="150" style="margin-bottom: -30px;"/><br>

# Hensu™

### Terraform for AI Agents.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://jdk.java.net/)
[![CI](https://github.com/hensu-project/hensu/actions/workflows/ci.yml/badge.svg)](https://github.com/hensu-project/hensu/actions/workflows/ci.yml)
[![Native Image](https://github.com/hensu-project/hensu/actions/workflows/native.yml/badge.svg)](https://github.com/hensu-project/hensu/actions/workflows/native.yml)
[![Protocol](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)

</div>

---

Hensu is a workflow engine for multi-agent systems. You define graphs in a type-safe Kotlin DSL, run them
locally with real AI agents, and push the compiled artifact to a self-hosted server. The CLI and the server
share the same execution engine – a workflow that passes locally is production-ready.

The server ships as a GraalVM native binary. No JVM to manage, no classpath to debug.

<div align="center">

[Get Started](#getting-started) • [Architecture](docs/unified-architecture.md) • [DSL Reference](docs/dsl-reference.md) • [Documentation](docs)

</div>

---

## Workflow Example

Two agents, two quality layers: a rubric scores the draft automatically; a reviewer agent approves
or sends it back for revision.

```kotlin
fun contentPipeline() = workflow("content-pipeline") {
    agents {
        agent("writer")   { role = "Content Writer";   model = Models.CLAUDE_SONNET_4_5 }
        agent("reviewer") { role = "Content Reviewer"; model = Models.GPT_4O }
    }

    rubrics { rubric("content-quality", "content-quality.md") }

    state {
        input("topic", VarType.STRING)
        variable("draft", VarType.STRING, "the full written article text")
    }

    graph {
        start at "write"

        node("write") {
            agent  = "writer"
            prompt = "Write a short article about {topic}. {recommendation}"
            writes("draft")
            rubric = "content-quality"
            onScore {
                whenScore lessThan 70.0 goto "write"  // score too low – retry
            }
            onSuccess goto "review"
        }

        node("review") {
            agent  = "reviewer"
            prompt = "Review this article: {draft}. Is it good enough to publish?"
            writes("draft")
            onApproval  goto "done"
            onRejection goto "write"                  // rejected – loop back
        }

        end("done", ExitStatus.SUCCESS)
    }
}
```

Run it locally against real agents:

```bash
hensu run content-pipeline -d working-dir -v -c '{"topic": "The Fermi Paradox"}'
```

The same artifact, unchanged, deploys to the server. See the [DSL Reference](docs/dsl-reference.md) for
parallel branches, consensus, dynamic planning, fork/join, and sub-workflows.

---

## Why Hensu?

| Alternative                           | The Problem                                                                                                                     | Hensu's Approach                                                                                                                               |
|:--------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------|
| **Temporal / Camunda / Airflow**      | General-purpose durable execution requires significant worker setup and serialization contracts before any business logic runs. | A workflow is a standalone compiled artifact. Run it locally with the CLI; push to the server when it is ready. No separate integration phase. |
| **LangGraph / CrewAI / AutoGen**      | Python's execution model constrains true parallelism. AI logic tends to couple tightly to application code.                     | Java 25 virtual threads run branches in genuine parallel. Workflows are independent artifacts – decoupled from your application codebase.      |
| **LangChain4J / Spring AI / Embabel** | Graph construction lives inside application code. The workflow and the application are entangled from day one.                  | The DSL compiles to a portable JSON definition. Validate the graph in isolation before it touches any application code.                        |
| **Custom in-house orchestrators**     | Mixing orchestration with tool execution creates an implicit remote-execution surface that grows with the system.               | A hard boundary via MCP split-pipe. The server orchestrates; tools execute on tenant clients. No user code runs on the server.                 |

---

## Capabilities

| Feature                      | Description                                                                                                                                                        |
|:-----------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **CLI + Server, One Engine** | The CLI and the server run the same `hensu-core`. Run locally with real agents, push the same artifact to production – no rework, no glue code.                    |
| **Zero-Trust MCP Boundary**  | Tool calls never execute inside the orchestrator. The split-pipe transport routes requests to tenant clients – a hallucinating LLM cannot run code on your server. |
| **True Parallelism**         | Java 25 virtual threads power parallel branches, consensus evaluation, and multi-tenant isolation – no GIL, no cooperative scheduling.                             |
| **Non-Linear Flow**          | Loops, conditional branches, parallel fan-out with consensus (majority, unanimous, weighted, judge-decides), fork/join, and sub-workflows.                         |
| **Agentic Planning**         | Static (predefined) or dynamic (LLM-generated) execution plans within nodes, with mid-plan review gates.                                                           |
| **Rubric Evaluation**        | Automated quality gates score outputs against markdown rubric definitions and route on thresholds – self-correcting loops without custom parsing code.             |
| **Time-Travel Backtracking** | Rewind to any previous node mid-flight, optionally edit the prompt in `$EDITOR`, and re-execute. Full audit trail via `ExecutionHistory`.                          |
| **Resilience**               | PostgreSQL-backed checkpoints. If the server restarts mid-execution, it resumes automatically from the last checkpoint via atomic lease recovery.                  |
| **Multi-Tenancy**            | Java 25 `ScopedValues` enforce strict tenant isolation – no data leaks between concurrent workflows.                                                               |
| **Local Daemon**             | `hensu run` starts a warm resident process. Detach with `Ctrl+C` and re-attach with `hensu attach` – output is never lost.                                         |

---

## Hensu Stack

Hensu separates **authoring** (managed by you) from **execution** (managed by the server).

### Toolchain

- **[hensu-dsl](hensu-dsl/README.md):** A Kotlin DSL that compiles `.kt` files into static, versioned
  Workflow Definitions (JSON).
- **[hensu-cli](hensu-cli/README.md):** Runs workflows locally via `hensu run`. Also manages the full server
  lifecycle: `build`, `push`, `pull`, `delete`, `list`.

### Runtime

- **[hensu-core](hensu-core/README.md):** A pure Java library that hydrates Definitions into executable
  graphs. Handles state transitions, rubric evaluation, and agent interactions.
- **[hensu-server](hensu-server/README.md):** A multi-tenant server that executes workflows. Its core feature
  is the **SSE split-pipe** for MCP: a secure tunnel that routes tool execution to tenant clients without
  requiring open inbound ports.

### Shared Infrastructure

- **[hensu-serialization](hensu-serialization/README.md):** Jackson mixins and serializers that convert
  between domain objects and their JSON representation. Used by both the CLI and the server.

---

## Architecture

The architecture follows a strict **split-pipe** model:

```
 Developer (local)                                              Hensu Runtime               External

 Author:    +––––––––––+    +––––––––––+
            │ Kotlin   │–––>│  hensu   │
            │ DSL      │    │  run     │
            +––––––––––+    +––––––––––+

 Deploy:    +––––––––––+    +––––––––––+    +––––––––––+    +––––––––––––––––––––––+    +––––––––––––––+
            │  hensu   │    │   JSON   │    │  hensu   │    │  Hensu Server        │    │ LLMs (Claude │
            │  build   │–––>│   Def.   │–––>│  push    │–––>│  (GraalVM Native)    │<––>│ GPT, Gemini) │
            +––––––––––+    +––––––––––+    +––––––––––+    │                      │    +––––––––––––––+
                                                            │  Core Engine         │    +––––––––––––––+
                                                            │  +– State Manager    │<––>│ MCP Tool     │
                                                            │  +– Rubric Evaluator │    │ Servers      │
                                                            │  +– Consensus Engine │    +––––––––––––––+
                                                            +––––––––––––––––––––––+
```

1. **Author:** Write a workflow in the Kotlin DSL. Run it locally with `hensu run` against real agents –
   the same execution engine the server uses.
2. **Build:** `hensu build` compiles the DSL into a static Workflow Definition (JSON).
3. **Push:** `hensu push` delivers the Definition to the server's persistence layer.
4. **Execute:** The server hydrates the Definition and begins execution. When a tool is needed, the
   split-pipe pushes the request to the tenant client via SSE; the client executes locally and posts the
   result back. No user code runs on the server.

---

## Security

Hensu is designed for zero-trust environments. The server is a pure orchestrator.

| Principle              | Implementation                                                                                                                                                                                                  |
|:-----------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **No Local Execution** | The server has no shell, no `eval`, no script runner. All side effects route through MCP to tenant clients.                                                                                                     |
| **Tenant Isolation**   | Every execution runs inside a Java `ScopedValue` boundary. No data leaks between concurrent workflows.                                                                                                          |
| **No Inbound Ports**   | Tenant clients connect outbound via SSE. No firewall rules required on the client side.                                                                                                                         |
| **Stateless Server**   | Workflow state is externalized to pluggable repositories. The server can be restarted at any point without losing execution state.                                                                              |
| **Input Validation**   | All API inputs are validated at the boundary. Identifiers use a restricted character set; free-text fields are sanitized against control character injection.                                                   |
| **Output Validation**  | LLM-generated outputs are validated before entering workflow state. ASCII control characters, Unicode manipulation characters (RTL overrides, zero-width chars, BOM), and oversized payloads are rejected.      |
| **Native Binary**      | GraalVM native image eliminates classpath scanning, reflection, and dynamic class loading attack surfaces.                                                                                                      |

---

## Getting Started

No database. No Docker. No JWT required for local development.

### 1. Install the CLI

```bash
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/install.sh | bash
```

### 2. Get the example workflows

Clone the repo to get the `working-dir/` examples:

```bash
git clone https://github.com/hensu-project/hensu.git && cd hensu
```

```
working-dir/
├── workflows/                  # Kotlin DSL workflow definitions
│   └── content-pipeline.kt
├── prompts/                    # agent prompt templates
│   └── agent-prompt.md
├── rubrics/                    # markdown scoring criteria
│   └── content-quality.md
└── build/                      # compiled output (hensu build)
    └── content-pipeline.json
```

### 3. Set your API key

```bash
hensu credentials set ANTHROPIC_API_KEY   # or OPENAI_API_KEY / GEMINI_API_KEY
```

### 4. Run locally

```bash
hensu run content-pipeline -d working-dir -v -c '{"topic": "The Fermi Paradox"}'
```

### Deploy to the Server

Start the pre-built native binary (no JVM, no DB, no JWT in `inmem` mode).

> **Note:** Pre-built binaries are currently available for Linux x86_64 only. On macOS or Windows, build from source with `./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native`.

Download the latest binary from [Releases](https://github.com/hensu-project/hensu/releases):

```bash
curl -L https://github.com/hensu-project/hensu/releases/download/server/<VERSION>/hensu-server-linux-x86_64 \
  -o hensu-server && chmod +x hensu-server
QUARKUS_PROFILE=inmem ./hensu-server

# In a second terminal:
hensu build content-pipeline -d working-dir
hensu push content-pipeline -d working-dir --server http://localhost:8080
curl -s -X POST http://localhost:8080/api/v1/executions \
  -H "Content-Type: application/json" \
  -d '{"workflowId": "content-pipeline", "context": {"topic": "AI Agents"}}'
```

For production setup with JWT auth and PostgreSQL, see the
[Server Developer Guide](docs/developer-guide-server.md#local-development).

---

## Integration Example

The [`integrations/spring-reference-client`](integrations/spring-reference-client/README.md) directory
contains a standalone Spring Boot application demonstrating a full end-to-end integration with `hensu-server`:

| What it shows              | How                                                                                       |
|:---------------------------|:------------------------------------------------------------------------------------------|
| **MCP split-pipe**         | Client connects outbound via SSE; server pushes tool requests back over the same channel  |
| **Real MCP tools**         | `fetch_customer_data` and `calculate_risk_score` implemented as local Spring beans        |
| **SSE event streaming**    | Reactive subscriber printing live execution events to the console                         |
| **Human-in-the-loop gate** | Workflow pauses after analysis; reviewer approves or rejects via `POST /demo/review/{id}` |

**Scenario:** a credit risk analyst agent evaluates a fictional credit-limit increase for customer `C-42`.

```bash
QUARKUS_PROFILE=inmem ./hensu-server
hensu build risk-assessment -d integrations/spring-reference-client/working-dir && \
hensu push risk-assessment -d integrations/spring-reference-client/working-dir --server http://localhost:8080
cd integrations/spring-reference-client && ./gradlew bootRun
```

---

## Legal

- [LICENSE](LICENSE) (Apache 2.0)
- [NOTICE](NOTICE)
- [TRADEMARK](https://github.com/hensu-project/.github/blob/main/TRADEMARK.md)
- [CONTRIBUTING](https://github.com/hensu-project/.github/blob/main/CONTRIBUTING.md)

Hensu™ and the axolotl logo are trademarks of Aleksandr Suvorov.
Copyright 2025-2026 Aleksandr Suvorov. All rights reserved.

---

<div align="center">

Built with

Java 25 • Kotlin DSL • Quarkus • GraalVM Native Image • MCP Protocol

</div>
