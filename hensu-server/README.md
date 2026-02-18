# Hensu™ Server

Quarkus-based HTTP server for multi-tenant AI workflow execution with MCP (Model Context Protocol) integration.

## Overview

The `hensu-server` module extends `hensu-core` with:

- **REST API** for workflow definition management and execution
- **Multi-Tenant Isolation** using Java 25 ScopedValues
- **MCP Gateway** for external tool integration (server never executes locally)
- **Dynamic Planning** via LLM-based plan generation
- **Human-in-the-Loop** support with plan review workflows
- **SSE Streaming** for real-time execution monitoring

## Architecture

The server is a **GraalVM native image** that receives pre-compiled workflow JSON from the CLI.
It initializes core infrastructure via `HensuFactory.builder()` and delegates all tool execution
to external MCP servers.

```
+—————————————————————————————————————————————————————————————————+
│                         hensu-server                            │
│  +——————————————————+  +—————————————+  +———————————————————+   │
│  │  REST API        │  │ MCP Gateway │  │  Agentic Executor │   │
│  │  (Workflows +    │  │ (JSON-RPC)  │  │  (Plan + Execute) │   │
│  │   Executions)    │  │             │  │                   │   │
│  +————————+—————————+  +——————+——————+  +————————+——————————+   │
│           │                   │                  │              │
│  +————————+———————————————————+——————————————————+———————————+  │
│  │  Server Runtime                                           │  │
│  │  +————————————————+  +——————————————+                     │  │
│  │  │ ServerAction   │  │ TenantContext│                     │  │
│  │  │ Executor (MCP) │  │ (ScopedValue)│                     │  │
│  │  +————————————————+  +——————————————+                     │  │
│  +———————————————————————————+———————————————————————————————+  │
│                              │                                  │
│  +———————————————————————————+———————————————————————————————+  │
│  │  hensu-core (HensuEnvironment via HensuFactory)           │  │
│  │  WorkflowExecutor │ AgentRegistry │ PlanExecutor          │  │
│  │  RubricEngine │ WorkflowRepository │ StateRepository      │  │
│  +———————————————————————————————————————————————————————————+  │
+—————————————————————————————————————————————————————————————————+
```

## Quick Start

### Running the Server

```bash
# Development mode with hot reload
./gradlew :hensu-server:quarkusDev

# Production build
./gradlew :hensu-server:build
java -jar hensu-server/build/quarkus-app/quarkus-run.jar
```

### Push a Workflow (CLI → Server)

```bash
# Build (compile DSL to JSON) then push to server
hensu build workflow.kt -d working-dir
hensu push my-workflow

# Or directly via curl
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d @workflow.json

# Response (201 Created)
{"id": "order-processing", "version": "1.0.0", "created": true}
```

### Start an Execution

```bash
curl -X POST http://localhost:8080/api/v1/executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt>" \
  -d '{"workflowId": "order-processing", "context": {"orderId": "ORD-456"}}'

# Response (202 Accepted)
{"executionId": "exec-abc-123", "workflowId": "order-processing"}
```

## REST API

All API and MCP endpoints require a valid JWT bearer token (`Authorization: Bearer <jwt>`).
Tenant identity is extracted from the `tenant_id` claim. In dev/test mode, authentication
is disabled and a default tenant is used (see [Local Development Tokens](#local-development-tokens)).

### Workflow Definition Management

Terraform/kubectl-style operations for managing workflow definitions (CLI integration).

| Method   | Path                             | Description                      |
|----------|----------------------------------|----------------------------------|
| `POST`   | `/api/v1/workflows`              | Push workflow (create or update) |
| `GET`    | `/api/v1/workflows`              | List all workflows for tenant    |
| `GET`    | `/api/v1/workflows/{workflowId}` | Pull workflow definition         |
| `DELETE` | `/api/v1/workflows/{workflowId}` | Delete workflow                  |

### Execution Operations

Runtime operations for starting and managing workflow executions (client integration).

| Method | Path                                      | Description                 |
|--------|-------------------------------------------|-----------------------------|
| `POST` | `/api/v1/executions`                      | Start workflow execution    |
| `GET`  | `/api/v1/executions/{executionId}`        | Get execution status        |
| `POST` | `/api/v1/executions/{executionId}/resume` | Resume paused execution     |
| `GET`  | `/api/v1/executions/{executionId}/plan`   | Get pending plan for review |
| `GET`  | `/api/v1/executions/paused`               | List paused executions      |

### MCP Gateway (SSE Split-Pipe Transport)

Implements MCP (Model Context Protocol) over SSE using a "split-pipe" architecture:

- **Downstream (SSE)**: Hensu pushes JSON-RPC tool call requests to connected clients
- **Upstream (HTTP POST)**: Clients send JSON-RPC responses back

```
+—————————————————+                    +—————————————————+
│  Hensu Engine   │                    │  Tenant Client  │
│                 │                    │  (MCP Server)   │
│  sendRequest()  │———— SSE ——————————>│  EventSource    │
│                 │  (tools/call)      │                 │
│                 │                    │                 │
│  handleResponse │<——— POST ——————————│  POST /message  │
│  (Future.done)  │  (result/error)    │                 │
+—————————————————+                    +—————————————————+
```

| Method | Path                        | Description                                          |
|--------|-----------------------------|------------------------------------------------------|
| `GET`  | `/mcp/connect?clientId=...` | SSE stream for receiving tool call requests          |
| `POST` | `/mcp/message`              | Submit JSON-RPC responses                            |
| `GET`  | `/mcp/status`               | Gateway status (connected clients, pending requests) |

### Event Streaming (SSE)

| Method | Path                                      | Description                      |
|--------|-------------------------------------------|----------------------------------|
| `GET`  | `/api/v1/executions/{executionId}/events` | SSE stream for execution events  |
| `GET`  | `/api/v1/executions/events`               | SSE stream for all tenant events |

### Input Validation & Error Responses

All identifiers in path and query parameters (`workflowId`, `executionId`, `clientId`) are
validated with the `@ValidId` constraint: alphanumeric start, followed by alphanumeric characters,
dots, hyphens, or underscores (1–255 chars). Workflow request bodies are deep-validated by
`@ValidWorkflow`, which walks the entire object graph to ensure all embedded identifiers are
well-formed and free-text fields (prompts, instructions, rubric content) are free of dangerous
control characters. `LogSanitizer` provides defense-in-depth by stripping CR/LF from user-derived
values at log call sites.

Invalid input returns `400 Bad Request` with a JSON error body:

```json
{"error": "workflowId: must be a valid identifier (alphanumeric, dots, hyphens, underscores; 1-255 chars)", "status": 400}
```

See the [Server Developer Guide — Input Validation](../docs/developer-guide-server.md#input-validation) for details.

### Execution Event Types

- `execution.started` - Execution began
- `plan.created` - Plan was generated (static or dynamic)
- `step.started` - Step execution began
- `step.completed` - Step finished (success or failure)
- `plan.revised` - Plan was modified after failure
- `plan.completed` - Plan execution finished
- `execution.paused` - Awaiting human review
- `execution.completed` - Workflow finished
- `execution.error` - Error occurred

### Example: Plan Review Workflow

```bash
# 1. Start execution (pauses for plan review)
curl -X POST http://localhost:8080/api/v1/executions \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"workflowId": "research", "context": {"topic": "quantum computing"}}'

# 2. Check pending plan
curl http://localhost:8080/api/v1/executions/exec-123/plan \
  -H "Authorization: Bearer $TOKEN"

# Response: {"planId": "plan-456", "totalSteps": 5, "currentStep": 0}

# 3. Approve and resume
curl -X POST http://localhost:8080/api/v1/executions/exec-123/resume \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"approved": true}'
```

## Key Components

### Server Initialization

The server initializes core infrastructure via CDI:

1. `HensuEnvironmentProducer` creates `HensuEnvironment` via `HensuFactory.builder()`
2. `ServerConfiguration` delegates core components for CDI injection
3. `ServerActionExecutor` provides MCP-only action execution (rejects local bash)

See [Server Developer Guide](../docs/developer-guide-server.md) for implementation details.

### Tenant Context

Thread-safe tenant isolation using Java 25 ScopedValues:

```java
TenantInfo tenant = TenantInfo.withMcp("tenant-123", "http://mcp.local:8080");
TenantContext.runAs(tenant, () -> {
    // All code in this scope has tenant context
    TenantInfo current = TenantContext.current();
    // MCP calls, DB queries, etc. are tenant-scoped
});
```

### Agentic Node Executor

Planning-aware executor for StandardNodes:

- **Static Plans**: Predefined steps from DSL
- **Dynamic Plans**: LLM-generated plans at runtime
- **Plan Revision**: Automatic retry with revised plans on failure
- **Human Review**: Optional pause before plan execution

### Persistence

Repository interfaces and in-memory defaults live in `hensu-core`. The server provides PostgreSQL
implementations via plain JDBC + Flyway. `HensuEnvironmentProducer` conditionally wires JDBC repos
when a DataSource is available, otherwise falls back to in-memory. Repositories are delegated from
`HensuEnvironment` via `@Produces @Singleton` — never created directly in CDI producers.

- `WorkflowRepository` (`io.hensu.core.workflow`) — Workflow definition storage
- `WorkflowStateRepository` (`io.hensu.core.state`) — Execution state snapshots (checkpoint/pause/resume)
- `JdbcWorkflowRepository` / `JdbcWorkflowStateRepository` — PostgreSQL implementations (JSONB columns)
- **Checkpoint hook**: `WorkflowExecutor` calls `listener.onCheckpoint(state)` before each node execution,
  enabling inter-node state persistence for failover recovery
- **`inmem` profile**: `quarkus.datasource.active=false` disables PostgreSQL for in-memory-only operation

## Configuration

### application.properties

```properties
# HTTP Server
quarkus.http.port=8080
quarkus.http.host=0.0.0.0
# MCP Configuration
hensu.mcp.connection-timeout=30s
hensu.mcp.read-timeout=60s
hensu.mcp.pool-size=10
# Planning Configuration
hensu.planning.default-max-steps=10
hensu.planning.default-max-replans=3
hensu.planning.default-timeout=5m
# PostgreSQL (Dev Services auto-starts a container in dev/test mode)
quarkus.datasource.db-kind=postgresql
%prod.quarkus.datasource.username=hensu
%prod.quarkus.datasource.password=hensu
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/hensu
# Flyway schema migrations
quarkus.flyway.migrate-at-start=true
quarkus.flyway.schemas=hensu
# In-memory profile (no PostgreSQL)
%inmem.quarkus.datasource.active=false
%inmem.quarkus.datasource.devservices.enabled=false
%inmem.quarkus.flyway.migrate-at-start=false
```

### Local Development Tokens

In **dev mode** (`quarkusDev`), JWT auth is permissive and `RequestTenantResolver` falls back to the
`hensu.tenant.default` property. No token is required for local development.

For **production-like testing** with real JWT validation, generate an RSA key pair and a signed token
outside the repository:

```bash
# 1. Generate RSA 2048-bit key pair (store outside the repo, e.g., ~/.hensu/)
mkdir -p ~/.hensu
openssl genrsa -out ~/.hensu/privateKey.pem 2048
openssl rsa -in ~/.hensu/privateKey.pem -pubout -out ~/.hensu/publicKey.pem

# 2. Point the server at the public key via environment variable
export HENSU_JWT_PUBLIC_KEY=~/.hensu/publicKey.pem

# 3. Generate a signed dev JWT
#    Required claims:
#      - "iss": must match mp.jwt.verify.issuer (default: "https://hensu.io")
#      - "sub": any subject identifier
#      - "tenant_id": tenant claim read by RequestTenantResolver
#      - "exp": expiration timestamp
HEADER=$(echo -n '{"alg":"RS256","typ":"JWT"}' | base64 -w0 | tr '+/' '-_' | tr -d '=')
PAYLOAD=$(echo -n "{\"iss\":\"https://hensu.io\",\"sub\":\"dev-user\",\"tenant_id\":\"dev-tenant\",\"exp\":$(($(date +%s) + 86400))}" | base64 -w0 | tr '+/' '-_' | tr -d '=')
SIGNATURE=$(echo -n "$HEADER.$PAYLOAD" | openssl dgst -sha256 -sign ~/.hensu/privateKey.pem | base64 -w0 | tr '+/' '-_' | tr -d '=')
export TOKEN="$HEADER.$PAYLOAD.$SIGNATURE"

# 4. Use the token
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @workflow.json

# Or pass to CLI
hensu push my-workflow --token "$TOKEN"
```

**Server JWT configuration** (in `application.properties`):

```properties
mp.jwt.verify.publickey.location=${HENSU_JWT_PUBLIC_KEY:publicKey.pem}
mp.jwt.verify.issuer=${HENSU_JWT_ISSUER:https://hensu.io}
```

## Module Structure

```
hensu-server/
├── src/main/java/io/hensu/server/
│   ├── action/                            # Server-specific action execution
│   │   └── ServerActionExecutor.java      # MCP-only (rejects local execution)
│   ├── api/                               # REST and SSE endpoints
│   │   ├── WorkflowResource.java          # Workflow definition management
│   │   ├── ExecutionResource.java         # Execution runtime operations
│   │   ├── ExecutionEventResource.java    # SSE endpoint for execution events
│   │   └── McpGatewayResource.java        # MCP SSE/POST endpoints
│   ├── validation/                        # Input validation (Bean Validation)
│   │   ├── InputValidator                  # Shared validation predicates (safe-ID, dangerous chars, size)
│   │   ├── ValidId.java                    # Custom identifier constraint
│   │   ├── ValidIdValidator.java           # Regex-based validator
│   │   ├── ValidMessage                    # Custom constraint for raw message body strings
│   │   ├── ValidMessageValidator     # Size-limit + control-character validator
│   │   ├── ValidWorkflow.java              # Custom constraint for Workflow bodies
│   │   ├── ValidWorkflowValidator.java     # Deep-validates workflow object graph
│   │   ├── LogSanitizer.java               # Strips CR/LF for log injection prevention
│   │   └── ConstraintViolationExceptionMapper.java  # Global 400 error mapper
│   ├── config/                            # CDI configuration
│   │   ├── HensuEnvironmentProducer.java  # HensuFactory → HensuEnvironment
│   │   ├── ServerBootstrap.java           # Startup registrations
│   │   └── ServerConfiguration.java       # CDI delegation + server beans
│   ├── executor/                          # Planning-aware execution
│   │   └── AgenticNodeExecutor.java
│   ├── mcp/                               # MCP integration (SSE split-pipe transport)
│   │   ├── JsonRpc.java
│   │   ├── McpConnection.java
│   │   ├── McpConnectionFactory.java
│   │   ├── McpConnectionPool.java
│   │   ├── McpSessionManager.java
│   │   ├── McpSidecar.java
│   │   └── SseMcpConnection.java
│   ├── persistence/                       # PostgreSQL persistence (plain JDBC)
│   │   ├── JdbcWorkflowRepository.java    # Workflow definitions (JSONB)
│   │   ├── JdbcWorkflowStateRepository.java # Execution state snapshots (JSONB)
│   │   └── PersistenceException.java      # Unchecked wrapper for SQLException
│   ├── planner/               # LLM planning
│   │   └── LlmPlanner.java
│   ├── service/               # Business logic
│   │   └── WorkflowService.java
│   ├── streaming/             # SSE event streaming
│   │   ├── ExecutionEvent.java
│   │   └── ExecutionEventBroadcaster.java
│   └── tenant/                # Multi-tenancy
│       ├── TenantContext.java
│       ├── TenantAware.java
│       └── TenantResolutionInterceptor.java
└── src/test/java/
    └── io/hensu/server/
        ├── action/
        ├── api/
        ├── config/
        ├── executor/
        ├── integration/       # Full-stack tests via IntegrationTestBase (inmem profile)
        ├── mcp/
        ├── persistence/       # JDBC repo tests via Testcontainers PostgreSQL
        ├── planner/
        ├── service/
        ├── streaming/
        └── tenant/
```

## Testing

```bash
# Run all tests
./gradlew :hensu-server:test

# Run specific test class
./gradlew :hensu-server:test --tests "*.AgenticNodeExecutorTest"

# Run integration tests (inmem profile, no Docker required)
./gradlew :hensu-server:test --tests "*.integration.*"

# Run JDBC repo tests (requires Docker for Testcontainers PostgreSQL)
./gradlew :hensu-server:test --tests "*.persistence.*"
```

## Dependencies

- **hensu-core**: Core workflow engine
- **hensu-serialization**: Jackson-based JSON serialization (provides `ObjectMapper` via
  `WorkflowSerializer.createMapper()`)
- **hensu-langchain4j-adapter**: LLM provider integration
- **Quarkus REST**: JAX-RS implementation
- **Quarkus Arc**: CDI container
- **Quarkus Scheduler**: Background tasks
- **Quarkus JDBC PostgreSQL**: PostgreSQL connection pooling (Agroal)
- **Quarkus Flyway**: Schema migration management

## See Also

- [hensu-core README](../hensu-core/README.md) - Core engine documentation
- [Unified Architecture](../docs/unified-architecture.md) - Architecture decisions and vision
- [DSL Reference](../docs/dsl-reference.md) - Workflow DSL syntax
- [Developer Guide](../docs/developer-guide-server.md) - Server development patterns
