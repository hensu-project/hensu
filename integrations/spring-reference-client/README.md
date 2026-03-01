# Hensu Spring Reference Client

A standalone Spring Boot application demonstrating end-to-end integration with `hensu-server`:
MCP split-pipe tool execution, SSE event streaming, and a human-in-the-loop review gate.

## Scenario

A credit risk analyst agent evaluates a fictional credit limit increase request for customer `C-42`.
The agent uses two MCP tools hosted in this client to gather data, then pauses for mandatory human approval before completing.

```
+————————————————————+     tools/list / tools/call (SSE)     +——————————————————+
│   hensu-server     │ ————————————————————————————————————> │  this client     │
│  (workflow engine) │                                       │  (MCP transport) │
+————————————————————+                                       +——————————————————+
         │                                                            │
         │ execution.paused                                   fetch_customer_data
         V                                                    calculate_risk_score
+————————————————————+
│  human reviewer    │  POST /demo/review/{id}  { "approved": true }
+————————————————————+
```

## Prerequisites

- Java 25+
- `hensu-server`
- `hensu` CLI on your `PATH`

## Setup

### 1 — Start hensu-server in inmem profile

```bash
./gradlew hensu-server:quarkusDev -Dquarkus.profile=inmem
```

The `inmem` profile disables JWT authentication and uses in-memory repositories. No database or token needed.

### 2 — Push the workflow

From the repo root:

```bash
./hensu build risk-assessment -d integrations/spring-reference-client/working-dir

./hensu push risk-assessment -d integrations/spring-reference-client/working-dir --server http://localhost:8080
```

### 3 — Start the reference client

```bash
cd integrations/spring-reference-client
./gradlew bootRun
```

The client starts on port `8081` and immediately:
1. Connects the MCP split-pipe to `/mcp/connect`
2. Submits an execution for `risk-assessment` with `customerId=C-42`
3. Streams SSE events to the console

## Human review

When the agent finishes its analysis the workflow pauses and the console prints the exact curl commands:

**Approve:**
```bash
curl -X POST http://localhost:8081/demo/review/{executionId} \
     -H 'Content-Type: application/json' \
     -d '{"approved":true,"modifications":{}}'
```

**Reject:**
```bash
curl -X POST http://localhost:8081/demo/review/{executionId} \
     -H 'Content-Type: application/json' \
     -d '{"approved":false,"modifications":{}}'
```

Replace `{executionId}` with the ID printed in the console output.

## JWT (non-inmem profiles)

Set the `HENSU_TOKEN` environment variable before starting the client:

```bash
export HENSU_TOKEN=eyJ...
./gradlew bootRun
```

The token is read from `${HENSU_TOKEN}` in `application.yml`. If the variable is unset the
token defaults to empty, which is correct for the `inmem` profile (auth disabled).

## Project structure

```
workflow/
  risk-assessment.kt          DSL workflow — push via hensu CLI
src/main/java/io/hensu/integrations/springclient/
  config/
    HensuProperties.java      @ConfigurationProperties(prefix="hensu")
    HensuClientConfig.java    WebClient + RestClient beans
  client/
    HensuClient.java          Blocking REST client (start / resume / result)
    HensuEventStream.java     Reactive SSE subscriber
  mcp/
    ToolHandler.java          Tool contract (name / description / schema / execute)
    ToolDispatcher.java       Routes tools/call and serves tools/list
    HensuMcpTransport.java    SSE split-pipe with exponential-backoff reconnect
  tools/
    FetchCustomerDataTool.java    Mock customer financial profile
    CalculateRiskScoreTool.java   Mock composite risk score (MEDIUM / REVIEW)
  review/
    ReviewController.java     POST /demo/review/{id} — forwards to hensu-server resume
  demo/
    DemoRunner.java           CommandLineRunner — wires the full scenario on startup
```
