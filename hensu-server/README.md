# Hensu Server

Quarkus-based HTTP server for multi-tenant AI workflow execution with MCP (Model Context Protocol) integration.

## Overview

The `hensu-server` module extends `hensu-core` with:

- **REST API** for workflow execution and management
- **Multi-Tenant Isolation** using Java 21+ ScopedValues
- **MCP Gateway** for external tool integration
- **Dynamic Planning** via LLM-based plan generation
- **Human-in-the-Loop** support with plan review workflows
- **HTTP/3 (QUIC)** experimental support for improved streaming performance
- **SSE Streaming** for real-time execution monitoring

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         hensu-server                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  REST API   │  │ MCP Gateway │  │  Agentic Executor       │  │
│  │  (JAX-RS)   │  │ (JSON-RPC)  │  │  (Planning + Execution) │  │
│  └──────┬──────┘  └──────┬──────┘  └───────────┬─────────────┘  │
│         │                │                     │                │
│  ┌──────┴────────────────┴─────────────────────┴─────────────┐  │
│  │                    Tenant Context                         │  │
│  │                   (ScopedValues)                          │  │
│  └──────┬────────────────────────────────────────────────────┘  │
│         │                                                       │
│  ┌──────┴──────────────────────────────────────────────────┐    │
│  │                     hensu-core                          │    │
│  │  WorkflowExecutor │ AgentRegistry │ RubricEngine        │    │
│  └─────────────────────────────────────────────────────────┘    │
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

### Execute a Workflow

```bash
# Start workflow execution
curl -X POST http://localhost:8080/api/v1/workflows/order-processing/execute \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: tenant-123" \
  -d '{"orderId": "ORD-456", "userId": "user-789"}'

# Response
{"executionId": "exec-abc-123", "workflowId": "order-processing"}
```

## REST API

All endpoints require the `X-Tenant-ID` header for multi-tenant isolation.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/workflows/{workflowId}/execute` | Start workflow execution |
| `POST` | `/api/v1/workflows/executions/{executionId}/resume` | Resume paused execution |
| `GET` | `/api/v1/workflows/executions/{executionId}` | Get execution status |
| `GET` | `/api/v1/workflows/executions/{executionId}/plan` | Get pending plan for review |
| `GET` | `/api/v1/workflows/executions/paused` | List paused executions |
| `GET` | `/api/v1/executions/{executionId}/events` | SSE stream for execution events |
| `GET` | `/api/v1/executions/events` | SSE stream for all tenant events |

### Execution Event Streaming (SSE)

Real-time execution monitoring via Server-Sent Events. Clients receive JSON events as execution progresses.

**Event Types:**
- `execution.started` - Execution began
- `plan.created` - Plan was generated (static or dynamic)
- `step.started` - Step execution began
- `step.completed` - Step finished (success or failure)
- `plan.revised` - Plan was modified after failure
- `plan.completed` - Plan execution finished
- `execution.paused` - Awaiting human review
- `execution.completed` - Workflow finished
- `execution.error` - Error occurred

**JavaScript Client Example:**

```javascript
const eventSource = new EventSource('/api/v1/executions/exec-123/events', {
    headers: { 'X-Tenant-ID': 'tenant-1' }
});

eventSource.addEventListener('step.started', (e) => {
    const data = JSON.parse(e.data);
    console.log(`Step ${data.stepIndex} started: ${data.toolName}`);
});

eventSource.addEventListener('step.completed', (e) => {
    const data = JSON.parse(e.data);
    console.log(`Step ${data.stepIndex} ${data.success ? 'succeeded' : 'failed'}`);
});

eventSource.addEventListener('execution.completed', (e) => {
    const data = JSON.parse(e.data);
    console.log(`Execution ${data.success ? 'succeeded' : 'failed'}`);
    eventSource.close();
});
```

### Example: Plan Review Workflow

```bash
# 1. Start execution (pauses for plan review)
curl -X POST http://localhost:8080/api/v1/workflows/research/execute \
  -H "X-Tenant-ID: tenant-123" \
  -d '{"topic": "quantum computing"}'

# 2. Check pending plan
curl http://localhost:8080/api/v1/workflows/executions/exec-123/plan \
  -H "X-Tenant-ID: tenant-123"

# Response: {"planId": "plan-456", "totalSteps": 5, "currentStep": 0}

# 3. Approve and resume
curl -X POST http://localhost:8080/api/v1/workflows/executions/exec-123/resume \
  -H "X-Tenant-ID: tenant-123" \
  -d '{"approved": true}'
```

## Key Components

### Tenant Context

Thread-safe tenant isolation using Java 21+ ScopedValues:

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

```java
// Execution modes
PlanningConfig.disabled()           // Simple agent call
PlanningConfig.forStatic()          // Use DSL-defined plan
PlanningConfig.forDynamic()         // Generate plan via LLM
PlanningConfig.forStaticWithReview() // Static plan + human review
```

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

**MCP Gateway Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/mcp/connect?clientId=...` | SSE stream for receiving tool call requests |
| `POST` | `/mcp/message` | Submit JSON-RPC responses |
| `GET` | `/mcp/status` | Gateway status (connected clients, pending requests) |

**Client Connection Example (JavaScript):**

```javascript
// Connect to SSE stream
const events = new EventSource('/mcp/connect?clientId=my-tenant');

events.onmessage = async (e) => {
    const request = JSON.parse(e.data);

    if (request.method === 'tools/call') {
        // Execute tool locally
        const result = await executeToolLocally(request.params);

        // Send response back via POST
        await fetch('/mcp/message', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                jsonrpc: '2.0',
                id: request.id,
                result: result
            })
        });
    }
};
```

**Server-Side Tool Call (Java 25 Virtual Threads):**

```java
// In ActionNodeExecutor - blocks virtual thread efficiently
McpConnection conn = connectionPool.getForTenant(tenantId);
Map<String, Object> result = conn.callTool("read_file", Map.of("path", "/etc/hosts"));
// Virtual thread is parked while waiting - OS thread is released
```

### MCP Sidecar (ActionHandler)

Routes tool calls to tenant-specific MCP servers via the ActionHandler interface:

```kotlin
// In workflow DSL
node("process") {
    action {
        send("mcp", mapOf(
            "tool" to "read_file",
            "arguments" to mapOf("path" to "/data/input.json")
        ))
    }
}
```

### Workflow Service

Business logic layer separating concerns from REST controllers:

```java
@Inject
WorkflowService workflowService;

// Start execution
ExecutionStartResult result = workflowService.startExecution(
    tenantId, workflowId, context);

// Get status
ExecutionStatus status = workflowService.getExecutionStatus(
    tenantId, executionId);

// Resume with decision
workflowService.resumeExecution(tenantId, executionId,
    ResumeDecision.approve());
```

## Configuration

### application.properties

```properties
# HTTP Server
quarkus.http.port=8080
quarkus.http.host=0.0.0.0

# HTTP/3 (QUIC) - requires TLS
quarkus.http.http3=true
quarkus.http.ssl-port=8443

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

### HTTP/3 Setup (Experimental)

> **Note:** HTTP/3 support in Quarkus is experimental. See [Quarkus HTTP Reference](https://quarkus.io/guides/http-reference#http3-experimental).

HTTP/3 uses QUIC protocol which requires TLS. Generate a development certificate:

```bash
# Generate self-signed certificate for development
keytool -genkeypair -alias hensu -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore hensu-keystore.p12 -validity 365 \
  -storepass changeit -dname "CN=localhost"

# Place in project root or configure path in application.properties
```

Configure TLS in `application.properties`:

```properties
# Development
%dev.quarkus.http.ssl.certificate.key-store-file=hensu-keystore.p12
%dev.quarkus.http.ssl.certificate.key-store-password=changeit

# Production (use proper certificates)
quarkus.http.ssl.certificate.files=/path/to/cert.pem
quarkus.http.ssl.certificate.key-files=/path/to/key.pem
```

**HTTP/3 Benefits for SSE Streaming:**
- Faster connection establishment (0-RTT)
- Better handling of packet loss (no head-of-line blocking)
- Improved performance for real-time event streaming
- Multiplexed streams over single connection

## Module Structure

```
hensu-server/
├── src/main/java/io/hensu/server/
│   ├── api/                    # REST and SSE endpoints
│   │   ├── WorkflowResource.java        # Workflow execution REST API
│   │   ├── ExecutionEventResource.java  # SSE endpoint for execution events
│   │   └── McpGatewayResource.java      # MCP SSE/POST endpoints
│   ├── config/                 # CDI configuration
│   │   ├── ServerBootstrap.java
│   │   └── ServerConfiguration.java
│   ├── executor/               # Planning-aware execution
│   │   └── AgenticNodeExecutor.java
│   ├── mcp/                    # MCP integration (SSE split-pipe transport)
│   │   ├── JsonRpc.java             # JSON-RPC 2.0 message helper
│   │   ├── McpConnection.java       # Connection interface
│   │   ├── McpConnectionFactory.java
│   │   ├── McpConnectionPool.java   # Connection pool (HTTP + SSE)
│   │   ├── McpSessionManager.java   # SSE session management
│   │   ├── McpSidecar.java          # ActionHandler implementation
│   │   ├── SseMcpConnection.java    # SSE-based connection
│   │   ├── McpToolDiscovery.java
│   │   └── TenantToolRegistry.java
│   ├── streaming/              # SSE event streaming
│   │   ├── ExecutionEvent.java            # Event DTOs for execution monitoring
│   │   └── ExecutionEventBroadcaster.java # PlanObserver for event broadcasting
│   ├── persistence/            # State persistence
│   │   ├── WorkflowStateRepository.java
│   │   └── InMemoryWorkflowStateRepository.java
│   ├── planner/                # LLM planning
│   │   └── LlmPlanner.java
│   ├── service/                # Business logic
│   │   └── WorkflowService.java
│   └── tenant/                 # Multi-tenancy
│       ├── TenantContext.java
│       ├── TenantAware.java
│       └── TenantResolutionInterceptor.java
└── src/test/java/              # Tests
    └── io/hensu/server/
        ├── api/
        ├── executor/
        ├── integration/
        ├── mcp/
        ├── persistence/
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
- **hensu-langchain4j-adapter**: LLM provider integration
- **Quarkus REST**: JAX-RS implementation
- **Quarkus Arc**: CDI container
- **Quarkus Scheduler**: Background tasks

## See Also

- [hensu-core README](../hensu-core/README.md) - Core engine documentation
- [DSL Reference](../docs/dsl-reference.md) - Workflow DSL syntax
- [Developer Guide](../docs/developer-guide.md) - Architecture details
