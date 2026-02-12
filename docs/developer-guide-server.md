# Hensu Server Developer Guide

This guide covers the architecture, patterns, and best practices for developing the `hensu-server` module.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Server Initialization](#server-initialization)
- [Package Structure](#package-structure)
- [Multi-Tenancy](#multi-tenancy)
- [REST API Development](#rest-api-development)
- [SSE Streaming](#sse-streaming)
- [MCP Integration](#mcp-integration)
- [Testing](#testing)
  - [Integration Testing](#integration-testing)
- [GraalVM Native Image](#graalvm-native-image)
- [Configuration](#configuration)

---

## Architecture Overview

The server module extends `hensu-core` with HTTP capabilities. Core infrastructure is initialized
via `HensuFactory.builder()` - **never** by constructing components directly.

```
+——————————————————————————————————————————————————————————————————————+
│                            hensu-server                              │
│                                                                      │
│  +———————————————————————————————————————————————————————————————+   │
│  │                       api/ (REST + SSE)                       │   │
│  │  WorkflowResource │ ExecutionResource │ ExecutionEventResource│   │
│  +—————————————————————————————+—————————————————————————————————+   │
│                                │                                     │
│  +—————————————————————————————+—————————————————————————————————+   │
│  │                      service/                                 │   │
│  │                       WorkflowService                         │   │
│  +—————————————————————————————+—————————————————————————————————+   │
│                                │                                     │
│  +—————————————————————————————+—————————————————————————————————+   │
│  │  streaming/                 │        mcp/                     │   │
│  │  (SSE Events)               │  (MCP Split-Pipe)               │   │
│  +—————————————————————————————+—————————————————————————————————+   │
│                                │                                     │
│  +————————————+————————————————+————————————————+————————————————+   │
│  │ action/    │          config/                │   tenant/      │   │
│  │ Server     │  HensuEnvironmentProducer       │  TenantContext │   │
│  │ Action     │  ServerConfiguration            │  (ScopedValue) │   │
│  │ Executor   │  ServerBootstrap                │                │   │
│  +————————————+—————————————————————————————————+————————————————+   │
│                                │                                     │
+————————————————————————————————+—————————————————————————————————————+
                                 │
                      +——————————+——————————+
                      │     hensu-core      │
                      │  (HensuEnvironment) │
                      │  WorkflowExecutor   │
                      │  AgentRegistry      │
                      │  PlanExecutor       │
                      │  ToolRegistry       │
                      +—————————————————————+
```

### Request Flow

1. HTTP request arrives at REST resource (`api/`)
2. Tenant ID extracted from `X-Tenant-ID` header
3. `TenantContext` established for the request scope
4. Service layer processes business logic
5. Core engine executes workflow
6. Events broadcast via SSE to subscribed clients

---

## Server Initialization

The server **MUST** use `HensuFactory.builder()` to create core infrastructure.
This is wired through CDI in three classes:

### HensuEnvironmentProducer

Creates the `HensuEnvironment` singleton via `HensuFactory`:

```java
@Produces
@ApplicationScoped
public HensuEnvironment hensuEnvironment() {
    Properties properties = extractHensuProperties();
    hensuEnvironment = HensuFactory.builder()
            .config(HensuConfig.builder().useVirtualThreads(true).build())
            .loadCredentials(properties)
            .actionExecutor(actionExecutor)  // ServerActionExecutor (MCP-only)
            .build();
    registerGenericHandlers();
    return hensuEnvironment;
}
```

### ServerConfiguration

Delegates `HensuEnvironment` components for CDI injection and produces server-specific beans.
Repository instances are created inside `HensuFactory` (defaulting to in-memory implementations)
and exposed here as CDI beans via delegation:

```java
// Core components delegated from HensuEnvironment
@Produces @Singleton
public WorkflowExecutor workflowExecutor(HensuEnvironment env) {
    return env.getWorkflowExecutor();
}

@Produces @Singleton
public AgentRegistry agentRegistry(HensuEnvironment env) {
    return env.getAgentRegistry();
}

// Repositories — created by HensuFactory, delegated for CDI injection
@Produces @Singleton
public WorkflowRepository workflowRepository(HensuEnvironment env) {
    return env.getWorkflowRepository();
}

@Produces @Singleton
public WorkflowStateRepository workflowStateRepository(HensuEnvironment env) {
    return env.getWorkflowStateRepository();
}

// Shared ObjectMapper with Hensu serialization support
@Produces @Singleton
public ObjectMapper objectMapper() {
    return WorkflowSerializer.createMapper();
}
```

> **Note**: Use `@Singleton` (not `@ApplicationScoped`) for delegate producers. `@ApplicationScoped` creates a CDI client proxy that breaks `instanceof` checks against concrete types (e.g., `InMemoryWorkflowStateRepository`).

### ServerActionExecutor

Server-specific `ActionExecutor` that **only supports MCP requests** (no local execution):

```java
@Override
public ActionResult execute(Action action, Map<String, Object> context) {
    return switch (action) {
        case Action.Send send -> executeSend(send, context);
        case Action.Execute exec -> ActionResult.failure(
            "Server mode does not support local command execution");
    };
}
```

### Common Mistakes

- **NEVER** create `WorkflowExecutor`, `AgentRegistry`, or other core components directly
- **NEVER** create a `StubAgent` manually — `HensuFactory` has stub mode built-in
- **NEVER** create repository instances directly in server producers — delegate from `HensuEnvironment`
- **NEVER** support local command execution in server mode

---

## Package Structure

```
io.hensu.server/
├── action/                # Server-specific action execution
│   └── ServerActionExecutor     # MCP-only (rejects Action.Execute)
│
├── api/                   # HTTP endpoints (REST + SSE)
│   ├── WorkflowResource        # Workflow definition management (push/pull/delete/list)
│   ├── ExecutionResource        # Execution runtime (start/resume/status/plan)
│   ├── ExecutionEventResource   # Execution monitoring SSE
│   └── McpGatewayResource       # MCP split-pipe SSE/POST
│
├── config/                # CDI configuration
│   ├── HensuEnvironmentProducer # HensuFactory → HensuEnvironment
│   ├── ServerBootstrap          # Startup registrations
│   └── ServerConfiguration      # CDI delegation + server beans
│
├── executor/              # Planning-aware execution
│   └── AgenticNodeExecutor      # StandardNode executor with planning
│
├── mcp/                   # MCP protocol implementation
│   ├── JsonRpc                  # JSON-RPC 2.0 message helper
│   ├── McpSessionManager        # SSE session management
│   ├── McpConnection            # Connection interface
│   ├── McpConnectionPool        # Connection pooling
│   ├── McpSidecar               # ActionHandler for MCP tools
│   └── SseMcpConnection         # SSE-based connection impl
│
├── planner/               # LLM planning
│   └── LlmPlanner              # LLM-based plan generation
│
├── service/               # Business logic layer
│   └── WorkflowService          # Workflow operations
│
├── streaming/             # Execution event streaming
│   ├── ExecutionEvent           # Event DTOs (sealed interface)
│   └── ExecutionEventBroadcaster # PlanObserver + broadcaster
│
└── tenant/                # Multi-tenancy
    ├── TenantContext            # ScopedValue-based context
    ├── TenantAware              # Marker interface
    └── TenantResolutionInterceptor
```

---

## Multi-Tenancy

### TenantContext

Uses Java 25 `ScopedValue` for thread-safe tenant isolation:

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

### API Separation

The REST API is split into two distinct resources:

1. **WorkflowResource** (`/api/v1/workflows`) - Workflow definition management (CLI integration)
   - Push, pull, delete, list workflow definitions
   - Uses `WorkflowRepository` directly

2. **ExecutionResource** (`/api/v1/executions`) - Execution runtime (client integration)
   - Start, resume, status, plan
   - Uses `WorkflowService` for business logic

**Do not mix** definition management with execution in the same resource.

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

| Status                    | Usage                                     |
|---------------------------|-------------------------------------------|
| 200 OK                    | Successful GET, PUT, POST with body       |
| 201 Created               | Resource created (workflow push - new)    |
| 202 Accepted              | Async operation started (execution start) |
| 204 No Content            | Successful DELETE                         |
| 400 Bad Request           | Invalid input, missing headers            |
| 404 Not Found             | Resource not found                        |
| 500 Internal Server Error | Unexpected errors                         |

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

### Integration Test Pattern (REST)

```java
@QuarkusTest
class MyResourceIT {

    @Test
    void shouldPushWorkflow() {
        given()
            .header("X-Tenant-ID", "test-tenant")
            .contentType(ContentType.JSON)
            .body(workflowJson)
        .when()
            .post("/api/v1/workflows")
        .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("created", equalTo(true));
    }

    @Test
    void shouldStartExecution() {
        given()
            .header("X-Tenant-ID", "test-tenant")
            .contentType(ContentType.JSON)
            .body(Map.of("workflowId", "my-workflow", "context", Map.of()))
        .when()
            .post("/api/v1/executions")
        .then()
            .statusCode(202)
            .body("executionId", notNullValue());
    }
}
```

### Integration Testing

Full-stack integration tests exercise the workflow engine end-to-end within a bootstrapped Quarkus context, using the [Stub Agent System](developer-guide-core.md#stub-agent-system) to intercept all model requests.

#### Test Infrastructure

| Class                  | Role                                                             |
|------------------------|------------------------------------------------------------------|
| `IntegrationTestBase`  | Abstract base: CDI injection, state cleanup, helper methods      |
| `TestActionHandler`    | Records action payloads for plan/action dispatch assertions      |
| `TestReviewHandler`    | Scriptable review decisions (approve, backtrack, reject)         |
| `TestValidatorHandler` | Generic node handler for `"validator"` type nodes                |
| `TestPauseHandler`     | Generic node handler that pauses on first call, succeeds on next |

All infrastructure lives in `io.hensu.server.integration` (package-private).

#### IntegrationTestBase

Every integration test extends `IntegrationTestBase`, which provides:

- **CDI-injected beans**: `workflowRepository`, `workflowStateRepository`, `workflowService`, `agentRegistry`, `hensuEnvironment`
- **Per-test cleanup** (`@BeforeEach`): clears stub responses, workflow repository, and workflow state repository
- **Helper methods**:

| Method                                           | Description                                             |
|--------------------------------------------------|---------------------------------------------------------|
| `loadWorkflow(resourceName)`                     | Loads JSON fixtures from `/workflows/`                  |
| `pushAndExecute(workflow, context)`              | Saves workflow + executes under `TEST_TENANT`           |
| `pushAndExecuteWithMcp(workflow, ctx, endpoint)` | Executes with MCP-enabled tenant context                |
| `registerStub(key, response)`                    | Programmatic stub registration by node ID or agent ID   |
| `registerStub(scenario, key, response)`          | Scenario-specific stub registration                     |
| `resolveRubricPath(resourceName)`                | Copies classpath rubric to temp file for `RubricParser` |

#### Writing an Integration Test

```java
@QuarkusTest
class MyWorkflowIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldExecuteWorkflow() {
        // 1. Load workflow fixture
        Workflow workflow = loadWorkflow("my-workflow.json");

        // 2. Register stub responses by node ID
        registerStub("research", "Research findings about the topic");
        registerStub("draft", "Article draft based on research");

        // 3. Execute
        ExecutionStartResult result = pushAndExecute(
                workflow, Map.of("topic", "AI"));

        // 4. Assert on final snapshot
        List<HensuSnapshot> snapshots = workflowStateRepository
                .findByWorkflowId(TEST_TENANT, result.workflowId());
        assertThat(snapshots).isNotEmpty();

        HensuSnapshot snapshot = snapshots.getLast();
        assertThat(snapshot.checkpointReason()).isEqualTo("completed");
        assertThat(snapshot.context())
                .containsEntry("research", "Research findings about the topic");
    }
}
```

Register stubs by **node ID** (e.g., `"research"`, `"draft"`), not by agent ID. The `StandardNodeExecutor` propagates the current node ID into the execution context, so `StubResponseRegistry` resolves node-ID-based stubs first. See [Stub Agent System — Response Resolution Order](developer-guide-core.md#response-resolution-order) for the full lookup chain.

#### Scripting Review Decisions

`TestReviewHandler` is a CDI `@Alternative` (priority 1) that replaces the default `AUTO_APPROVE` handler. Enqueue decisions before execution — they are consumed in FIFO order:

```java
@Inject TestReviewHandler testReviewHandler;

@BeforeEach
void resetReviewHandler() {
    testReviewHandler.reset();
}

@Test
void shouldBacktrackAndRetry() {
    Workflow workflow = loadWorkflow("review-workflow.json");
    registerStub("research", "Research");
    registerStub("draft", "Content");

    // First review: backtrack to "research" node
    testReviewHandler.enqueueDecision(
            new ReviewDecision.Backtrack("research", null, "Needs more detail"));
    // Second review: approve
    testReviewHandler.enqueueDecision(new ReviewDecision.Approve());

    ExecutionStartResult result = pushAndExecute(
            workflow, Map.of("topic", "test"));

    HensuSnapshot snapshot = /* ... get last snapshot ... */;
    List<BacktrackEvent> backtracks = snapshot.history().getBacktracks();
    assertThat(backtracks).isNotEmpty();
    assertThat(backtracks.getFirst().getFrom()).isEqualTo("draft");
    assertThat(backtracks.getFirst().getTo()).isEqualTo("research");
}
```

When the queue is empty, `TestReviewHandler` falls back to a configurable default (approve by default). Use `setDefaultDecision()` to change the fallback.

#### Verifying Action Dispatch

`TestActionHandler` records all payloads dispatched to the `"test-tool"` handler ID:

```java
@Inject TestActionHandler testActionHandler;
@Inject ActionExecutor actionExecutor;

@BeforeEach
void resetActionHandler() {
    testActionHandler.reset();
    actionExecutor.registerHandler(testActionHandler);
}

@Test
void shouldDispatchPlanSteps() {
    Workflow workflow = loadWorkflow("plan-static.json");
    registerStub("execute", "Plan execution complete");

    pushAndExecute(workflow, Map.of("task", "test"));

    List<Map<String, Object>> payloads = testActionHandler.getReceivedPayloads();
    assertThat(payloads).hasSize(2);
    assertThat(payloads.getFirst()).containsEntry("action", "search");
    assertThat(payloads.get(1)).containsEntry("action", "process");
}
```

#### Workflow JSON Fixtures

Place workflow definitions in `src/test/resources/workflows/`. Use `model: "stub"` for all agents:

```json
{
  "id": "my-workflow",
  "version": "1.0.0",
  "startNode": "process",
  "agents": {
    "writer": { "id": "writer", "role": "writer", "model": "stub", "temperature": 0.7 }
  },
  "nodes": {
    "process": {
      "id": "process",
      "nodeType": "STANDARD",
      "agentId": "writer",
      "prompt": "Write about {topic}",
      "transitionRules": [{ "type": "success", "targetNode": "done" }]
    },
    "done": { "id": "done", "nodeType": "END", "status": "SUCCESS" }
  }
}
```

#### Rubric Testing

Pre-register parsed rubrics so the executor skips filesystem path resolution:

```java
private Rubric parseAndRegisterRubric(String rubricId, String resourceName) {
    String rubricPath = resolveRubricPath(resourceName);
    Rubric parsed = RubricParser.parse(Path.of(rubricPath));

    Rubric rubric = Rubric.builder()
            .id(rubricId)
            .name(parsed.getName())
            .version(parsed.getVersion())
            .type(parsed.getType())
            .passThreshold(parsed.getPassThreshold())
            .criteria(parsed.getCriteria())
            .build();

    hensuEnvironment.getRubricRepository().save(rubric);
    return rubric;
}
```

Place rubric markdown files in `src/test/resources/rubrics/`.

---

## GraalVM Native Image

The server is deployed as a GraalVM native image via Quarkus. All server code — and any dependency it pulls in — must be native-image safe. See the [hensu-core Developer Guide](developer-guide-core.md#graalvm-native-image-constraints) for the foundational rules (no reflection, no classpath scanning, no dynamic proxies, no runtime bytecode generation). This section covers **server-specific** concerns.

### How Quarkus Changes the Picture

Quarkus performs heavy build-time processing that relaxes some raw GraalVM constraints:

| Feature                            | Raw GraalVM                | With Quarkus                                               |
|------------------------------------|----------------------------|------------------------------------------------------------|
| CDI injection (`@Inject`)          | Requires reflection config | Works — Quarkus resolves beans at build time (ArC)         |
| `@ConfigProperty`                  | Requires reflection config | Works — processed at build time                            |
| JAX-RS resources (`@Path`, `@GET`) | Requires reflection config | Works — REST layer is build-time wired                     |
| Jackson `@JsonProperty` on DTOs    | Requires reflection config | Works — `quarkus-jackson` registers metadata               |
| `ServiceLoader`                    | Fails at runtime           | Works — Quarkus scans `META-INF/services` at build time    |
| LangChain4j AI services            | Requires reflection config | Works — `quarkus-langchain4j` extensions register metadata |

**Key insight**: Within Quarkus-managed code, standard annotations and CDI work normally. The constraints only bite when you introduce code that Quarkus doesn't know about — custom reflection, third-party libraries without Quarkus extensions, or `hensu-core` internals that bypass the framework.

### Adding New Dependencies

When adding a new library to `hensu-server`:

1. **Check if a Quarkus extension exists.** Search [extensions catalog](https://quarkus.io/extensions/) first. Extensions provide build-time metadata, so you get native-image support automatically.

2. **If an extension exists**, add the Quarkus extension (not the raw library):
   ```kotlin
   // build.gradle.kts
   implementation("io.quarkus:quarkus-my-library")  // Quarkus extension
   // NOT: implementation("org.example:my-library")  // raw library
   ```

3. **If no extension exists**, you must verify native-image compatibility:
   - Run `./gradlew hensu-server:build -Dquarkus.native.enabled=true`
   - Test the binary: `./hensu-server/build/hensu-server-*-runner`
   - If it fails with `ClassNotFoundException` or `NoSuchMethodException`, add reflection configuration:
     ```json
     // src/main/resources/reflect-config.json
     [
       {
         "name": "com.example.SomeClass",
         "allDeclaredConstructors": true,
         "allPublicMethods": true
       }
     ]
     ```

4. **Pin the version to match Quarkus BOM.** If the library is managed by the Quarkus BOM (e.g., Jackson, Vert.x), do not override the version. Mismatched versions cause subtle native-image failures.

### CDI Producers and Native Image

CDI producers in `ServerConfiguration` are native-image safe because Quarkus processes them at build time. Follow these patterns:

```java
// SAFE — delegates from HensuEnvironment
@Produces @Singleton
public WorkflowExecutor workflowExecutor(HensuEnvironment env) {
    return env.getWorkflowExecutor();
}

// SAFE — delegates repository created by HensuFactory
@Produces @Singleton
public WorkflowRepository workflowRepository(HensuEnvironment env) {
    return env.getWorkflowRepository();
}

// UNSAFE — dynamic class loading in a producer
@Produces @Singleton
public Object dynamicBean() {
    return Class.forName(config.getClassName()).newInstance();  // fails in native
}
```

### Server-Specific Notes

**Mutiny reactive types are safe.** `Uni`, `Multi`, `BroadcastProcessor` all work in native image — Quarkus handles their registration.

**MCP JSON-RPC uses explicit Jackson.** The `JsonRpc` class uses `ObjectMapper` directly with `readTree`/`writeValueAsString` — no reflection-based deserialization. This is intentionally safe for native image.

### Verifying Native Image Compatibility

```bash
# Full native build (slow — run before releases, not on every change)
./gradlew hensu-server:build -Dquarkus.native.enabled=true -Dquarkus.package.type=native

# Quick JVM-mode test (catches most issues except native-specific ones)
./gradlew hensu-server:test

# Native integration tests
./gradlew hensu-server:test -Dquarkus.test.native-image-profile=true
```

### Quick Reference (Server-Specific)

| Pattern                                             | Safe  | Notes                                     |
|-----------------------------------------------------|-------|-------------------------------------------|
| `@Inject` / `@Produces`                             | Yes   | Quarkus ArC — build-time CDI              |
| `@ConfigProperty`                                   | Yes   | Build-time processed                      |
| Quarkus extensions                                  | Yes   | Provide native metadata                   |
| Raw third-party libs                                | Maybe | Need `reflect-config.json` if reflective  |
| `ObjectMapper.readTree()`                           | Yes   | No reflection — tree-model parsing        |
| `new ObjectMapper().readValue(json, MyClass.class)` | Maybe | Needs registration unless Quarkus-managed |
| Mutiny `Uni`/`Multi`                                | Yes   | Quarkus-managed                           |

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
- Use `HensuFactory.builder()` for core infrastructure (never construct directly)
- Keep workflow definition management separate from execution operations
- Use service layer for business logic, resources for HTTP concerns
- Prefer constructor injection over field injection
- Use sealed interfaces for event types (exhaustive pattern matching)
- Clean up SSE subscriptions on disconnect
- Log at appropriate levels (INFO for requests, DEBUG for details)

### Don't

- Don't bypass `HensuFactory` by constructing core components directly
- Don't create `StubAgent` manually (use built-in stub mode)
- Don't create repository instances directly in server producers — delegate from `HensuEnvironment`
- Don't support local command execution in server mode
- Don't put business logic in REST resources
- Don't expose internal exceptions to clients
- Don't block on I/O in reactive streams (use virtual threads or async)
- Don't mix definition management with execution in same REST resource

---

## See Also

- [README.md](../hensu-server/README.md) - Module overview and quick start
- [Unified Architecture](unified-architecture.md) - Architecture decisions and vision
- [hensu-core Developer Guide](developer-guide-core.md) - Core engine documentation
- [DSL Reference](dsl-reference.md) - Workflow DSL syntax
