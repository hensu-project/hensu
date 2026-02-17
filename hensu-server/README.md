# Hensu Server

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
┌─────────────────────────────────────────────────────────────────┐
│                         hensu-server                            │
│  ┌──────────────────┐  ┌─────────────┐  ┌───────────────────┐   │
│  │  REST API        │  │ MCP Gateway │  │  Agentic Executor │   │
│  │  (Workflows +    │  │ (JSON-RPC)  │  │  (Plan + Execute) │   │
│  │   Executions)    │  │             │  │                   │   │
│  └────────┬─────────┘  └──────┬──────┘  └────────┬──────────┘   │
│           │                   │                  │              │
│  ┌────────┴───────────────────┴──────────────────┴───────────┐  │
│  │  Server Runtime                                           │  │
│  │  ┌────────────────┐  ┌──────────────┐                     │  │
│  │  │ ServerAction   │  │ TenantContext│                     │  │
│  │  │ Executor (MCP) │  │ (ScopedValue)│                     │  │
│  │  └────────────────┘  └──────────────┘                     │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              │                                  │
│  ┌───────────────────────────┴───────────────────────────────┐  │
│  │  hensu-core (HensuEnvironment via HensuFactory)           │  │
│  │  WorkflowExecutor │ AgentRegistry │ PlanExecutor │        │  │
│  │  RubricEngine │ WorkflowRepository │ StateRepository      │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
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
  -H "X-Tenant-ID: tenant-123" \
  -d @workflow.json

# Response (201 Created)
{"id": "order-processing", "version": "1.0.0", "created": true}
```

### Start an Execution

```bash
curl -X POST http://localhost:8080/api/v1/executions \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: tenant-123" \
  -d '{"workflowId": "order-processing", "context": {"orderId": "ORD-456"}}'

# Response (202 Accepted)
{"executionId": "exec-abc-123", "workflowId": "order-processing"}
```

## REST API

All endpoints require the `X-Tenant-ID` header for multi-tenant isolation.

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
┌─────────────────┐                    ┌─────────────────┐
│  Hensu Engine   │                    │  Tenant Client  │
│                 │                    │  (MCP Server)   │
│  sendRequest()  │──── SSE ──────────>│  EventSource    │
│                 │  (tools/call)      │                 │
│                 │                    │                 │
│  handleResponse │<─── POST ──────────│  POST /message  │
│  (Future.done)  │  (result/error)    │                 │
└─────────────────┘                    └─────────────────┘
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
  -H "X-Tenant-ID: tenant-123" \
  -d '{"workflowId": "research", "context": {"topic": "quantum computing"}}'

# 2. Check pending plan
curl http://localhost:8080/api/v1/executions/exec-123/plan \
  -H "X-Tenant-ID: tenant-123"

# Response: {"planId": "plan-456", "totalSteps": 5, "currentStep": 0}

# 3. Approve and resume
curl -X POST http://localhost:8080/api/v1/executions/exec-123/resume \
  -H "X-Tenant-ID: tenant-123" \
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

Repository interfaces and in-memory defaults live in `hensu-core`. The server delegates them from
`HensuEnvironment` via `@Produces @Singleton` — it never creates instances directly.

- `WorkflowRepository` (`io.hensu.core.workflow`) — Workflow definition storage
- `WorkflowStateRepository` (`io.hensu.core.state`) — Execution state snapshots (pause/resume)

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
# Database (optional)
# quarkus.datasource.db-kind=postgresql
# quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/hensu
```

## Module Structure

```
hensu-server/
├── src/main/java/io/hensu/server/
│   ├── action/                # Server-specific action execution
│   │   └── ServerActionExecutor.java    # MCP-only (rejects local execution)
│   ├── api/                   # REST and SSE endpoints
│   │   ├── WorkflowResource.java        # Workflow definition management
│   │   ├── ExecutionResource.java       # Execution runtime operations
│   │   ├── ExecutionEventResource.java  # SSE endpoint for execution events
│   │   └── McpGatewayResource.java      # MCP SSE/POST endpoints
│   ├── config/                # CDI configuration
│   │   ├── HensuEnvironmentProducer.java  # HensuFactory → HensuEnvironment
│   │   ├── ServerBootstrap.java           # Startup registrations
│   │   └── ServerConfiguration.java       # CDI delegation + server beans
│   ├── executor/              # Planning-aware execution
│   │   └── AgenticNodeExecutor.java
│   ├── mcp/                   # MCP integration (SSE split-pipe transport)
│   │   ├── JsonRpc.java
│   │   ├── McpConnection.java
│   │   ├── McpConnectionFactory.java
│   │   ├── McpConnectionPool.java
│   │   ├── McpSessionManager.java
│   │   ├── McpSidecar.java
│   │   └── SseMcpConnection.java
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
        ├── integration/       # Full-stack tests via IntegrationTestBase
        ├── mcp/
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

# Run integration tests
./gradlew :hensu-server:test --tests "*.integration.*"
```

## Dependencies

- **hensu-core**: Core workflow engine
- **hensu-serialization**: Jackson-based JSON serialization (provides `ObjectMapper` via
  `WorkflowSerializer.createMapper()`)
- **hensu-langchain4j-adapter**: LLM provider integration
- **Quarkus REST**: JAX-RS implementation
- **Quarkus Arc**: CDI container
- **Quarkus Scheduler**: Background tasks

## See Also

- [hensu-core README](../hensu-core/README.md) - Core engine documentation
- [Unified Architecture](../docs/unified-architecture.md) - Architecture decisions and vision
- [DSL Reference](../docs/dsl-reference.md) - Workflow DSL syntax
- [Developer Guide](../docs/developer-guide-server.md) - Server development patterns
