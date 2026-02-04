# Hensu Server Developer Guide

This guide covers the architecture, patterns, and best practices for developing the `hensu-server` module.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Package Structure](#package-structure)
- [Multi-Tenancy](#multi-tenancy)
- [REST API Development](#rest-api-development)
- [SSE Streaming](#sse-streaming)
- [MCP Integration](#mcp-integration)
- [Testing](#testing)
- [Configuration](#configuration)

---

## Architecture Overview

The server module extends `hensu-core` with HTTP capabilities:

```
┌─────────────────────────────────────────────────────────────────────┐
│                           hensu-server                              │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                        api/ (REST + SSE)                     │   │
│  │  WorkflowResource │ ExecutionEventResource │ McpGatewayResource │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┼───────────────────────────────────┐ │
│  │                     service/                                  │ │
│  │                      WorkflowService                          │ │
│  └───────────────────────────┼───────────────────────────────────┘ │
│                              │                                      │
│  ┌──────────────┬────────────┴────────────┬────────────────────┐   │
│  │  streaming/  │        mcp/             │    persistence/    │   │
│  │  (SSE Events)│  (MCP Split-Pipe)       │  (State Storage)   │   │
│  └──────────────┴─────────────────────────┴────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐ │
│  │                      tenant/ (Multi-Tenancy)                  │ │
│  │                        TenantContext                          │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                              │                                      │
└──────────────────────────────┼──────────────────────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    │     hensu-core      │
                    │  WorkflowExecutor   │
                    │  AgentRegistry      │
                    │  PlanExecutor       │
                    └─────────────────────┘
```

### Request Flow

1. HTTP request arrives at REST resource (`api/`)
2. Tenant ID extracted from `X-Tenant-ID` header
3. `TenantContext` established for the request scope
4. Service layer processes business logic
5. Core engine executes workflow
6. Events broadcast via SSE to subscribed clients

---

## Package Structure

```
io.hensu.server/
├── api/                    # HTTP endpoints (REST + SSE)
│   ├── WorkflowResource         # Workflow execution REST API
│   ├── ExecutionEventResource   # Execution monitoring SSE
│   └── McpGatewayResource       # MCP split-pipe SSE/POST
│
├── config/                 # CDI configuration
│   ├── ServerBootstrap          # Startup registrations
│   └── ServerConfiguration      # Configuration beans
│
├── executor/               # Planning-aware execution
│   └── AgenticNodeExecutor      # StandardNode executor with planning
│
├── mcp/                    # MCP protocol implementation
│   ├── JsonRpc                  # JSON-RPC 2.0 message helper
│   ├── McpSessionManager        # SSE session management
│   ├── McpConnection            # Connection interface
│   ├── McpConnectionPool        # Connection pooling
│   ├── McpSidecar               # ActionHandler for MCP tools
│   └── SseMcpConnection         # SSE-based connection impl
│
├── streaming/              # Execution event streaming
│   ├── ExecutionEvent           # Event DTOs (sealed interface)
│   └── ExecutionEventBroadcaster # PlanObserver + broadcaster
│

│
├── service/                # Business logic layer
│   └── WorkflowService          # Workflow operations
│
└── tenant/                 # Multi-tenancy
    ├── TenantContext            # ScopedValue-based context
    ├── TenantAware              # Marker interface
    └── TenantResolutionInterceptor
```

---

## Multi-Tenancy

### TenantContext

Uses Java 21+ `ScopedValue` for thread-safe tenant isolation:

```java
// In REST resource or interceptor
TenantInfo tenant = TenantInfo.withMcp(tenantId, mcpEndpoint);
TenantContext.runAs(tenant, () -> {
    // All code in this scope sees the tenant
    TenantInfo current = TenantContext.current();

    // Core engine, MCP calls, DB queries are tenant-scoped
    workflowExecutor.execute(workflow, context);
});
```

### Adding Tenant-Aware Components

1. Inject or access `TenantContext.current()` where needed
2. Use tenant ID for data isolation (DB queries, caches)
3. Route MCP calls to tenant-specific endpoints

```java
@ApplicationScoped
public class TenantAwareRepository {

    public List<Workflow> findAll() {
        String tenantId = TenantContext.current().tenantId();
        return db.query("SELECT * FROM workflows WHERE tenant_id = ?", tenantId);
    }
}
```

---

## REST API Development

### Creating a New Resource

```java
@Path("/api/v1/myresource")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MyResource {

    private final MyService service;

    @Inject
    public MyResource(MyService service) {
        this.service = service;
    }

    @GET
    @Path("/{id}")
    public Response get(
            @PathParam("id") String id,
            @HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        // Business logic via service layer
        MyEntity entity = service.findById(tenantId, id);

        return Response.ok(entity).build();
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BadRequestException("X-Tenant-ID header is required");
        }
    }
}
```

### Response Conventions

| Status | Usage |
|--------|-------|
| 200 OK | Successful GET, PUT, POST with body |
| 201 Created | Resource created (include Location header) |
| 202 Accepted | Async operation started |
| 204 No Content | Successful DELETE or POST without body |
| 400 Bad Request | Invalid input, missing headers |
| 404 Not Found | Resource not found |
| 500 Internal Server Error | Unexpected errors |

---

## SSE Streaming

### Two SSE Patterns in hensu-server

1. **ExecutionEventResource** - One-way event streaming for monitoring
2. **McpGatewayResource** - Split-pipe bidirectional communication

### Adding New Event Types

1. Add record to `ExecutionEvent` sealed interface:

```java
public sealed interface ExecutionEvent {
    // ... existing events ...

    /// My new event type.
    record MyNewEvent(
            String executionId,
            String customField,
            Instant timestamp) implements ExecutionEvent {

        @Override
        public String type() {
            return "my.new.event";
        }

        public static MyNewEvent now(String executionId, String customField) {
            return new MyNewEvent(executionId, customField, Instant.now());
        }
    }
}
```

2. Update `ExecutionEventBroadcaster.convertEvent()` if mapping from `PlanEvent`:

```java
private ExecutionEvent convertEvent(String executionId, PlanEvent event) {
    return switch (event) {
        // ... existing cases ...
        case PlanEvent.MyNewPlanEvent e -> ExecutionEvent.MyNewEvent.now(
                executionId, e.customField());
    };
}
```

3. Publish directly where needed:

```java
broadcaster.publish(executionId, ExecutionEvent.MyNewEvent.now(executionId, "value"));
```

### Creating a New SSE Endpoint

```java
@GET
@Path("/my-stream")
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.APPLICATION_JSON)
public Multi<MyEvent> streamMyEvents(@QueryParam("filter") String filter) {

    return Multi.createFrom().emitter(emitter -> {
        // Register emitter for events
        myEventSource.subscribe(filter, emitter::emit);

        // Cleanup on disconnect
        emitter.onTermination(() -> {
            myEventSource.unsubscribe(filter);
        });
    });
}
```

### BroadcastProcessor Pattern

For fan-out to multiple subscribers:

```java
@ApplicationScoped
public class MyBroadcaster {

    private final Map<String, BroadcastProcessor<MyEvent>> processors =
            new ConcurrentHashMap<>();

    public Multi<MyEvent> subscribe(String channel) {
        BroadcastProcessor<MyEvent> processor = processors.computeIfAbsent(
                channel, k -> BroadcastProcessor.create());
        return processor;
    }

    public void publish(String channel, MyEvent event) {
        BroadcastProcessor<MyEvent> processor = processors.get(channel);
        if (processor != null) {
            processor.onNext(event);
        }
    }

    public void complete(String channel) {
        BroadcastProcessor<MyEvent> processor = processors.remove(channel);
        if (processor != null) {
            processor.onComplete();
        }
    }
}
```

---

## MCP Integration

### Split-Pipe Architecture

```
Downstream (SSE): Server → Client
  - JSON-RPC requests pushed via EventSource
  - Client receives tool call requests

Upstream (HTTP POST): Client → Server
  - JSON-RPC responses sent via POST /mcp/message
  - Server correlates by request ID
```

### Using MCP in Workflows

Via DSL action:

```kotlin
node("process") {
    action {
        send("mcp", mapOf(
            "tool" to "read_file",
            "arguments" to mapOf("path" to "/data/input.json")
        ))
    }
}
```

Via direct connection:

```java
McpConnection conn = connectionPool.getForTenant(tenantId);
Map<String, Object> result = conn.callTool("search", Map.of("query", "test"));
```

### Adding New MCP Methods

1. Update `McpSidecar` to handle new action types
2. Add method constants to `JsonRpc` if needed
3. Update client documentation

---

## Testing

### Unit Test Pattern

```java
class MyResourceTest {

    private MyService service;
    private MyResource resource;

    @BeforeEach
    void setUp() {
        service = mock(MyService.class);
        resource = new MyResource(service);
    }

    @Test
    void shouldReturnEntityWhenFound() {
        when(service.findById("tenant-1", "id-1"))
                .thenReturn(new MyEntity("id-1", "data"));

        Response response = resource.get("id-1", "tenant-1");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(service).findById("tenant-1", "id-1");
    }

    @Test
    void shouldReturn400WhenTenantIdMissing() {
        assertThatThrownBy(() -> resource.get("id-1", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("X-Tenant-ID");
    }
}
```

### SSE Test Pattern

```java
@Test
void shouldStreamEvents() {
    ExecutionEvent event1 = ExecutionEvent.ExecutionStarted.now("exec-1", "wf-1", "tenant-1");
    ExecutionEvent event2 = new ExecutionEvent.StepStarted(...);

    Multi<ExecutionEvent> mockStream = Multi.createFrom().items(event1, event2);
    when(broadcaster.subscribe("exec-1")).thenReturn(mockStream);

    Multi<ExecutionEvent> result = resource.streamEvents("exec-1", "tenant-1");

    AssertSubscriber<ExecutionEvent> subscriber = result
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));
    subscriber.awaitCompletion();

    assertThat(subscriber.getItems()).hasSize(2);
    assertThat(subscriber.getItems().get(0).type()).isEqualTo("execution.started");
}
```

### Integration Test Pattern

```java
@QuarkusTest
class MyResourceIT {

    @Test
    void shouldExecuteWorkflow() {
        given()
            .header("X-Tenant-ID", "test-tenant")
            .contentType(ContentType.JSON)
            .body(Map.of("input", "value"))
        .when()
            .post("/api/v1/workflows/my-workflow/execute")
        .then()
            .statusCode(202)
            .body("executionId", notNullValue());
    }
}
```

---

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

# Logging
quarkus.log.category."io.hensu".level=DEBUG
```

### Injecting Configuration

```java
@ApplicationScoped
public class MyComponent {

    @ConfigProperty(name = "hensu.mcp.connection-timeout", defaultValue = "30s")
    Duration connectionTimeout;

    @ConfigProperty(name = "hensu.planning.default-max-steps", defaultValue = "10")
    int maxSteps;
}
```

---

## Best Practices

### Do

- Always validate `X-Tenant-ID` header in REST resources
- Use service layer for business logic, resources for HTTP concerns
- Prefer constructor injection over field injection
- Use sealed interfaces for event types (exhaustive pattern matching)
- Clean up SSE subscriptions on disconnect
- Log at appropriate levels (INFO for requests, DEBUG for details)

### Don't

- Don't put business logic in REST resources
- Don't expose internal exceptions to clients
- Don't block on I/O in reactive streams (use virtual threads or async)
- Don't forget to remove pending futures on timeout
- Don't use mutable state in request-scoped beans

---

## See Also

- [README.md](../README.md) - Module overview and quick start
- [hensu-core Developer Guide](../../docs/developer-guide.md) - Core engine documentation
- [DSL Reference](../../docs/dsl-reference.md) - Workflow DSL syntax
