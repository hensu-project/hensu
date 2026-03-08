<div align="center">
    <img src="assets/logo.png" alt="hensu logo" width="150" style="margin-bottom: -30px;"/><br>

# Hensu™

### Terraform for AI Agents.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://jdk.java.net/)
[![CI](https://github.com/hensu-project/hensu/actions/workflows/ci.yml/badge.svg)](https://github.com/hensu-project/hensu/actions/workflows/ci.yml)
[![Native Image](https://github.com/hensu-project/hensu/actions/workflows/native.yml/badge.svg)](https://github.com/hensu-project/hensu/actions/workflows/native.yml)
[![Protocol](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)

**Define. Validate. Push. Run.**<br>
Self-hosted. Zero lock-in. No user code on the server.

[Get Started](#getting-started) • [Architecture](docs/unified-architecture.md) • [DSL Reference](docs/dsl-reference.md) • [Documentation](docs)

</div>

---

Traditional AI orchestrators demand weeks of SDK boilerplate before a single node executes — then you discover
the workflow isn't useful and rewrite everything. **Hensu** removes that tax entirely:

1. `hensu run --stub` — validate graph logic locally, zero API cost, zero infrastructure
2. `hensu run` — same command, real agents, same `hensu-core` engine as the server; what works here works in production
3. `hensu build` + `hensu push` — compile the same artifact, push to the server, integration is done

No rework. No glue code. No coupling AI logic to your application codebase.

## Key Capabilities

| Feature                       | Description                                                                                                                                                       |
|:------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Stub-First Development**    | Run full workflows locally with zero cost and zero API keys using deterministic stub agents. Validate graph logic before spending a single token.                 |
| **CLI + Server, Same Engine** | The CLI runs the same `hensu-core` as the server. Validate locally with real agents, push the same artifact to production. Zero rework.                           |
| **Zero-Trust MCP Boundary**   | Tool calls never execute inside the orchestrator. The split-pipe transport routes requests to tenant clients; a hallucinating LLM cannot run code on your server. |
| **True Parallelism**          | Java 25 virtual threads power parallel branches, consensus evaluation, and multi-tenant isolation — no Python GIL, no `async/await` coloring.                     |
| **Time-Travel Backtracking**  | Rewind to any previous node mid-flight, optionally edit the prompt in `$EDITOR`, and re-execute. Full audit trail via `ExecutionHistory`.                         |
| **Local Daemon**              | `hensu run` starts a warm JVM daemon. Detach with `Ctrl+C`, re-attach with `hensu attach` — output is never lost.                                                 |
| **Non-Linear Flow**           | Loops, conditional branches, parallel fan-out with consensus (majority, unanimous, weighted, judge-decides), fork/join, and sub-workflows.                        |
| **Agentic Planning**          | Static (predefined) or dynamic (LLM-generated) execution plans within nodes, with mid-plan review gates.                                                          |
| **Rubric Evaluation**         | Automated quality gates score LLM outputs against markdown rubric definitions and route on thresholds — self-correcting loops out of the box.                     |
| **Resilience**                | PostgreSQL-backed checkpoints. Crash the server mid-execution; it resumes automatically from the last checkpoint via atomic lease recovery.                       |
| **Multi-Tenancy**             | Java 25 `ScopedValues` enforce strict tenant isolation — no `ThreadLocal` leaks, no cross-tenant data bleed under concurrent virtual threads.                     |

### Why Hensu?

| Alternative                           | The Cost                                                                                                                         | Hensu's Answer                                                                                                   |
|:--------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------|
| **Temporal / Camunda / Airflow**      | Weeks of worker setup, SDK boilerplate, and serialization contracts before a single line of business logic runs.                 | Install the CLI, run a stub workflow in 5 minutes. Same artifact goes to production — no integration rework.     |
| **LangGraph / CrewAI / AutoGen**      | Python GIL kills true concurrency. `asyncio` is cooperative, not parallel. AI logic couples tightly to application code.         | Java 25 virtual threads. Real parallel execution. Workflows are standalone artifacts — decoupled from your app.  |
| **LangChain4J / Spring AI / Embabel** | Forces graph creation directly into your codebase. Weeks of integration to discover whether the workflow is even useful.         | DSL compiles to an independent artifact. Test the graph via CLI in minutes, without touching your core codebase. |
| **Custom in-house orchestrators**     | Inevitably mix orchestration with tool execution, creating a remote-code-execution surface. Rewrites happen when AI logic grows. | Hard security boundary via MCP split-pipe. Orchestrators only orchestrate. Tools run where you control them.     |

---

## The Hensu Stack

Hensu strictly separates **Development** (managed by you) from **Execution** (managed by the server).

### 1. The Toolchain (Compiler & CLI)

* **[hensu-dsl](hensu-dsl/README.md):** The Source. A Kotlin DSL that compiles `.kt` files into static, versioned
  **Workflow Definitions** (JSON).
* **[hensu-cli](hensu-cli/README.md):** The Manager. Runs workflows locally via `hensu run` (same engine as the
  server — stub agents, then real agents, zero rework). Also manages the full lifecycle against the server:
  `build`, `push`, `pull`, `delete`, `list`.

### 2. The Runtime (Server & Core)

* **[hensu-core](hensu-core/README.md):** The Engine. A pure Java library that hydrates static Definitions into
  executable graphs. It handles state transitions, rubric evaluation, and agent interactions.
* **[hensu-server](hensu-server/README.md):** The Platform. A multi-tenant server that executes workflows.
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

1. **Local execution:** `hensu run` runs the workflow locally — stub agents first (zero cost), then real agents. The
   same `hensu-core` engine powers both local runs and the server. What works here works in production.
2. **Compilation:** `hensu build` compiles the Kotlin DSL into a static **Workflow Definition** (JSON).
3. **Distribution:** `hensu push` sends the Definition to the Server's persistence layer.
4. **Execution:** The Server hydrates the Definition into an active instance. When a tool is needed, it uses the
   **Split-Pipe** transport:
    * **Downstream (SSE):** The Server pushes a JSON-RPC tool request to the connected tenant client.
    * **Upstream (HTTP):** The Client executes the tool locally and `POST`s the result back.

**No user code runs on the server.** The server is a pure orchestrator; all side effects happen on the client via MCP.

```
 Developer (local)                                              Hensu Runtime               External

 Develop:   +——————————+    +——————————+
            │ Kotlin   │———>│  hensu   │   (stubs first → real agents; same hensu-core as the server)
            │ DSL      │    │  run     │
            +——————————+    +——————————+

 Deploy:    +——————————+    +——————————+    +——————————+    +——————————————————————+    +——————————————+
            │  hensu   │    │   JSON   │    │  hensu   │    │  Hensu Server        │    │ LLMs (Claude │
            │  build   │———>│   Def.   │———>│  push    │———>│  (GraalVM Native)    │<——>│ GPT, Gemini) │
            +——————————+    +——————————+    +——————————+    │                      │    +——————————————+
                                                            │  Core Engine         │    +——————————————+
                                                            │  +— State Manager    │<——>│ MCP Tool     │
                                                            │  +— Rubric Evaluator │    │ Servers      │
                                                            │  +— Consensus Engine │    +——————————————+
                                                            +——————————————————————+
```

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

    rubrics { rubric("content-quality", "content-quality.md") }  // working-dir/rubrics/content-quality.md

    state {
        input("topic",    VarType.STRING)
        variable("draft",    VarType.STRING)
        variable("approved", VarType.BOOLEAN)
    }

    graph {
        start at "write"

        node("write") {
            agent  = "writer"
            prompt = "Write a short article about {topic}."
            writes("draft")
            rubric = "content-quality"
            onScore {
                whenScore lessThan 70.0 goto "write"  // score too low — retry
            }
            onSuccess goto "review"
        }

        node("review") {
            agent  = "reviewer"
            prompt = "Review this article: {draft}. Is it good enough to publish?"
            writes("approved")
            onApproval  goto "done"
            onRejection goto "write"                  // rejected — loop back
        }

        end("done", ExitStatus.SUCCESS)
    }
}
```

`rubric` evaluates the draft against markdown scoring criteria before the transition fires.
`writes("approved")` + `onApproval` / `onRejection` route on the reviewer's decision — no
parsing code required.

> The rubric file and more workflow examples ship with the repo in [`working-dir/`](working-dir/).
> Save the workflow above as `working-dir/workflows/content-pipeline.kt` and you're ready to build.

See the [DSL Reference](docs/dsl-reference.md) for parallel branches, consensus, dynamic planning,
fork/join, and sub-workflows.

---

## Getting Started

**Time to first execution: ~5 minutes.** No database. No Docker. No JWT.

### 1. Install the CLI

```bash
curl -sSL https://raw.githubusercontent.com/hensu-project/hensu/main/hensu-cli/scripts/install.sh | bash
```

### 2. Get the example workflows and rubrics

Shallow-clone the repo for the `working-dir/` examples — no build required:

```bash
git clone https://github.com/hensu-project/hensu.git && cd hensu
```

`working-dir/` contains ready-to-run workflow definitions and rubric files:

```
working-dir/
├── workflows/        # .kt workflow definitions
└── rubrics/          # markdown rubric scoring criteria
    └── content-quality.md
```

### 3. Set your API key

```bash
hensu credentials set ANTHROPIC_API_KEY=your-key   # or OPENAI_API_KEY / GEMINI_API_KEY
```

### 4. Run the example workflow locally

```bash
hensu run content-pipeline -d working-dir -v -c '{"topic": "The Fermi Paradox"}'
```

`-v` (verbose) prints node inputs and outputs to the console. The CLI runs the same `hensu-core` engine as the server — no server process needed for local development.

### Deploy to Server

When the workflow is ready for production, start the pre-built native server (no JVM, no DB, no JWT in `inmem` mode):

```bash
# Download and start
curl -L https://github.com/hensu-project/hensu/releases/latest/download/hensu-server-linux-x86_64 \
  -o hensu-server && chmod +x hensu-server
QUARKUS_PROFILE=inmem ./hensu-server

# Push and execute (new terminal)
hensu build content-pipeline -d working-dir
hensu push content-pipeline -d working-dir --server http://localhost:8080
curl -s -X POST http://localhost:8080/api/v1/executions \
  -H "Content-Type: application/json" \
  -d '{"workflowId": "content-pipeline", "context": {"topic": "AI Agents"}}'
```

For production setup with JWT auth and PostgreSQL see the [Server Developer Guide](docs/developer-guide-server.md#local-development).

### Agent-Native Workflow

Hensu is pre-configured with architectural rules in `.claude/rules/` and `.cursor/rules/`. You do not need to create
custom instructions or CLAUDE.md files.

The rule set supports an optional **Dual-Model Mode** that splits AI roles for cost and context efficiency:

| Role                   | Model              | Responsibility                                          |
|:-----------------------|:-------------------|:--------------------------------------------------------|
| **Lead Implementer**   | Claude / Cursor    | Architecture, code execution, final decisions           |
| **Cynical Researcher** | Gemini (1M window) | Codebase indexing, noise reduction, dependency vetting  |

To enable Dual-Model Mode, register the [Gemini MCP server](https://github.com/google-gemini/gemini-cli) in your MCP
config (e.g. `~/.claude/settings.json` for Claude Code, or your Cursor MCP settings). The rules auto-detect its
presence at session start via a ping probe:

- **Gemini available** → Gemini pre-filters the codebase; Claude implements against a minimal, high-signal index.
- **Gemini not available** → Falls back automatically to single-model mode. Claude handles both research and
  implementation using native search tools. No config changes or commented-out instructions required.

---

## Integration Example

The [`integrations/spring-reference-client`](integrations/spring-reference-client/README.md) directory contains a
standalone Spring Boot application that demonstrates a full end-to-end integration with `hensu-server`:

| What it shows              | How                                                                                      |
|:---------------------------|:-----------------------------------------------------------------------------------------|
| **MCP split-pipe**         | Client connects outbound via SSE; server pushes tool requests back over the same channel |
| **Real MCP tools**         | `fetch_customer_data` and `calculate_risk_score` implemented as local Spring beans       |
| **SSE event streaming**    | Reactive subscriber printing live execution events to the console                        |
| **Human-in-the-loop gate** | Workflow pauses after analysis; reviewer approves/rejects via `POST /demo/review/{id}`   |

**Scenario:** a credit risk analyst agent evaluates a fictional credit-limit increase for customer `C-42`. Run it with
three commands:

```bash
./gradlew hensu-server:quarkusDev -Dquarkus.profile=inmem   # start server (no DB, no JWT)
./hensu build risk-assessment -d integrations/spring-reference-client/working-dir && \
./hensu push risk-assessment -d integrations/spring-reference-client/working-dir --server http://localhost:8080  # push compiled workflow
cd integrations/spring-reference-client && ./gradlew bootRun # start demo client
```

---

## Security Model

Hensu is designed for Zero-Trust environments. The server is a **pure orchestrator** — it never executes
user-supplied code.

| Principle              | Implementation                                                                                                                                                                                                  |
|:-----------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **No Local Execution** | The server has no shell, no `eval`, no script runner. All side effects route through MCP to tenant clients.                                                                                                     |
| **Tenant Isolation**   | Every execution runs inside a Java `ScopedValue` boundary. No data leaks between concurrent workflows.                                                                                                          |
| **No Inbound Ports**   | The Split-Pipe transport means tenant clients connect *outbound* via SSE. No firewall rules required.                                                                                                           |
| **Stateless Server**   | Workflow state is externalized to pluggable repositories. The server can be killed and restarted at any time.                                                                                                   |
| **Input Validation**   | All API inputs are validated at the boundary. Identifiers are restricted to a safe character set; free-text fields are sanitized to reject control character injection.                                         |
| **Output Validation**  | LLM-generated node outputs are validated before entering workflow state. ASCII control characters, Unicode manipulation characters (RTL overrides, zero-width chars, BOM), and oversized payloads are rejected. |
| **Native Binary**      | GraalVM native image eliminates classpath scanning, reflection, and dynamic class loading attack surfaces.                                                                                                      |


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
