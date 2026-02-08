# Hensu Core Developer Guide

This guide covers API usage, adapter development, extension points, and testing strategies for developers working with or extending `hensu-core`.

## Table of Contents

- [API Usage](#api-usage)
  - [Quick Start](#quick-start)
  - [Using HensuFactory.Builder](#using-hensufactorybuilder)
  - [Executing Workflows](#executing-workflows)
- [Creating Custom Adapters](#creating-custom-adapters)
- [Generic Nodes](#generic-nodes)
- [Action Handlers](#action-handlers)
- [Rubric Engine](#rubric-engine)
- [Tool Registry](#tool-registry)
- [Plan Engine](#plan-engine)
- [Template Resolution](#template-resolution)
- [Human Review](#human-review)
- [Testing](#testing)
- [GraalVM Native Image Constraints](#graalvm-native-image-constraints)
- [Credentials Management](#credentials-management)
- [Key Files Reference](#key-files-reference)

## API Usage

### Quick Start

**Standalone with environment variables (stub provider only):**
```java
var env = HensuFactory.createEnvironment();
```

**With explicit providers (recommended):**
```java
var env = HensuFactory.builder()
    .config(HensuConfig.builder().useVirtualThreads(true).build())
    .loadCredentials(properties)
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
```

### Using HensuFactory.Builder

The builder provides fine-grained control over all components:

```java
HensuEnvironment env = HensuFactory.builder()
    // Configuration
    .config(HensuConfig.builder()
        .useVirtualThreads(true)
        .build())

    // Credentials (multiple loading strategies)
    .loadCredentials(properties)              // From Properties + env vars
    .loadCredentialsFromEnvironment()         // From env vars only
    .anthropicApiKey("sk-ant-...")            // Individual key
    .credential("CUSTOM_KEY", "value")        // Custom key

    // Agent providers (explicit wiring, GraalVM-safe)
    .agentProviders(List.of(new LangChain4jProvider()))

    // Optional components
    .stubMode(false)                          // Enable for testing
    .evaluatorAgent("evaluator")              // LLM-based rubric evaluation
    .reviewHandler(myReviewHandler)           // Human review support
    .actionExecutor(myActionExecutor)         // Action execution

    .build();
```

> **Note**: `StubAgentProvider` is always included automatically by `build()`. Do not add it explicitly.

### Executing Workflows

```java
// Get executor from environment
HensuEnvironment env = HensuFactory.builder()
    .loadCredentialsFromEnvironment()
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
WorkflowExecutor executor = env.getWorkflowExecutor();

// Register workflow agents
env.getAgentRegistry().registerAgents(workflow.getAgents());

// Execute with initial context
Map<String, Object> initialContext = Map.of("topic", "AI workflows");
ExecutionResult result = executor.execute(workflow, initialContext);

// Handle result
if (result instanceof ExecutionResult.Completed completed) {
    System.out.println("Success! Exit status: " + completed.getExitStatus());
    System.out.println("Final output: " + completed.getFinalOutput());
} else if (result instanceof ExecutionResult.Rejected rejected) {
    System.out.println("Rejected: " + rejected.getReason());
}
```

## Creating Custom Adapters

### 1. Create New Module

**build.gradle.kts**
```kotlin
dependencies {
    implementation(project(":hensu-core"))
    implementation("com.myai:myai-sdk:1.0.0")
}
```

### 2. Implement AgentProvider

```java
package io.hensu.adapter.myai;

import io.hensu.core.agent.spi.AgentProvider;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import java.util.Map;

public class MyAiProvider implements AgentProvider {

    @Override
    public String getName() {
        return "myai";
    }

    @Override
    public boolean supportsModel(String modelName) {
        return modelName.startsWith("myai-");
    }

    @Override
    public Agent createAgent(String agentId, AgentConfig config,
                            Map<String, String> credentials) {
        String apiKey = credentials.get("MYAI_API_KEY");
        return new MyAiAgent(agentId, config, apiKey);
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
```

### 3. Implement Agent

```java
package io.hensu.adapter.myai;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import java.util.Map;

public class MyAiAgent implements Agent {
    private final String id;
    private final AgentConfig config;
    private final MyAiClient client;

    public MyAiAgent(String id, AgentConfig config, String apiKey) {
        this.id = id;
        this.config = config;
        this.client = new MyAiClient(apiKey);
    }

    @Override
    public AgentResponse execute(String prompt, Map<String, Object> context) {
        try {
            MyAiResponse response = client.complete(prompt);
            return AgentResponse.success(response.getText(), response.getMetadata());
        } catch (Exception e) {
            return AgentResponse.failure(e);
        }
    }

    @Override
    public String getId() { return id; }

    @Override
    public AgentConfig getConfig() { return config; }
}
```

### 4. Wire Explicitly

Wire your provider via `HensuFactory.builder().agentProviders(...)`:

```java
var env = HensuFactory.builder()
    .agentProviders(List.of(
        new LangChain4jProvider(),
        new MyAiProvider()
    ))
    .loadCredentialsFromEnvironment()
    .build();
```

Or add a single provider:

```java
var env = HensuFactory.builder()
    .agentProvider(new LangChain4jProvider())
    .agentProvider(new MyAiProvider())
    .build();
```

### Provider Priority

When multiple providers support the same model, higher priority wins:

```java
public class LangChain4jProvider implements AgentProvider {
    public int getPriority() { return 100; }  // Preferred
}

public class MyAiProvider implements AgentProvider {
    public int getPriority() { return 50; }   // Fallback
}

// StubAgentProvider has priority 1000 when stub mode is enabled
```

## Generic Nodes

Generic nodes allow custom execution logic without involving an AI agent. They're useful for data validation, transformation, external service integration, and conditional branching.

### How Generic Nodes Work

1. Define a `GenericNode` in your workflow with an `executorType`
2. Create a `GenericNodeHandler` implementation with matching type
3. Register the handler with `NodeExecutorRegistry`
4. At runtime, `GenericNodeExecutor` looks up your handler and invokes it

### Creating a Handler

Implement the `GenericNodeHandler` interface:

```java
package com.example.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import java.util.Map;

public class ValidatorHandler implements GenericNodeHandler {

    public static final String TYPE = "validator";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) throws Exception {
        Map<String, Object> config = node.getConfig();
        String fieldName = (String) config.getOrDefault("field", "input");
        Object fieldValue = context.getState().getContext().get(fieldName);

        boolean isValid = fieldValue != null && !fieldValue.toString().isBlank();

        if (isValid) {
            return NodeResult.success("Validation passed",
                Map.of("validated_field", fieldName));
        } else {
            return NodeResult.failure(fieldName + " is required");
        }
    }
}
```

### Registering Handlers

#### Manual Registration (Pure Java)

```java
HensuEnvironment env = HensuFactory.builder()
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();

NodeExecutorRegistry registry = env.getNodeExecutorRegistry();
registry.registerGenericHandler("validator", new ValidatorHandler());
registry.registerGenericHandler("data-transformer", new DataTransformerHandler());
```

#### CDI Auto-Discovery (Quarkus/CDI)

With CDI, handlers annotated with `@ApplicationScoped` are automatically discovered and registered:

```java
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ValidatorHandler implements GenericNodeHandler {
    // ... same implementation
}
```

Both CLI and server register all CDI-discovered handlers via injection:

```java
@Inject Instance<GenericNodeHandler> genericNodeHandlers;

private void registerGenericHandlers() {
    for (GenericNodeHandler handler : genericNodeHandlers) {
        hensuEnvironment.getNodeExecutorRegistry()
            .registerGenericHandler(handler.getType(), handler);
    }
}
```

### Handler Best Practices

1. **Use descriptive type names**: `"user-validator"` is better than `"v1"`
2. **Access config safely**: Use `getOrDefault()` for optional parameters
3. **Store outputs in context**: Put results in `context.getState().getContext()` for subsequent nodes
4. **Return meaningful metadata**: Include relevant info in `NodeResult` metadata map
5. **Handle errors gracefully**: Return `NodeResult.failure()` with clear error messages

## Action Handlers

Action handlers send data from workflow actions to external systems. Handlers can implement any integration: HTTP calls, messaging (Slack, email), event publishing (Kafka, RabbitMQ), database operations, or custom logic.

### How Action Handlers Work

1. Implement the `ActionHandler` interface with your execution logic
2. Register the handler with `ActionExecutor`
3. Reference the handler by ID in workflow DSL using `send()`
4. At runtime, the executor looks up your handler and invokes it

### Creating a Handler

```java
package com.example.handlers;

import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.action.ActionHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class SlackHandler implements ActionHandler {

    private final String webhookUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public SlackHandler(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String getHandlerId() {
        return "slack";
    }

    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        try {
            String message = payload.getOrDefault("message", "Workflow event").toString();
            String body = String.format("{\"text\": \"%s\"}", message);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ActionResult.success("Slack notification sent");
            } else {
                return ActionResult.failure("Slack API error: " + response.statusCode());
            }
        } catch (Exception e) {
            return ActionResult.failure("Slack call failed: " + e.getMessage(), e);
        }
    }
}
```

### Registering Handlers

#### Manual Registration

```java
CLIActionExecutor executor = new CLIActionExecutor();

String slackUrl = System.getenv("SLACK_WEBHOOK_URL");
executor.registerHandler(new SlackHandler(slackUrl));

HensuEnvironment env = HensuFactory.builder()
    .actionExecutor(executor)
    .agentProviders(List.of(new LangChain4jProvider()))
    .build();
```

#### CDI Auto-Discovery

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SlackHandler implements ActionHandler {

    @Inject
    @ConfigProperty(name = "slack.webhook.url")
    String webhookUrl;

    @Override
    public String getHandlerId() {
        return "slack";
    }

    @Override
    public ActionResult execute(Map<String, Object> payload, Map<String, Object> context) {
        // Implementation using injected webhookUrl
    }
}
```

### Handler Best Practices

1. **Use descriptive handler IDs**: `"slack"`, `"github-dispatch"` are better than `"handler1"`
2. **Load credentials securely**: Use environment variables, config files, or secret managers
3. **Handle errors gracefully**: Return `ActionResult.failure()` with clear error messages
4. **Make handlers thread-safe**: They may be called concurrently

## Rubric Engine

The rubric engine evaluates output quality against defined criteria with score-based routing.

### Components

| Component                | Description                                                                 |
|--------------------------|-----------------------------------------------------------------------------|
| `RubricEngine`           | Orchestrates evaluation using repository and evaluator                      |
| `RubricRepository`       | Stores rubric definitions (in-memory by default)                            |
| `RubricEvaluator`        | Evaluates output against criteria                                           |
| `DefaultRubricEvaluator` | Self-evaluation: extracts scores from agent's own JSON output               |
| `LLMRubricEvaluator`     | External evaluation: uses a separate agent to assess output                 |
| `Rubric`                 | Immutable definition with pass threshold and weighted criteria              |
| `Criterion`              | Single evaluation dimension with weight, minimum score, and evaluation type |

### Self-Evaluation (Default)

`DefaultRubricEvaluator` extracts self-reported scores from the executing agent's JSON output. The agent includes a score and optional recommendations in its response:

```json
{
  "score": 75,
  "recommendation": "To improve, add more specific examples..."
}
```

Score keys searched (in order): `score`, `self_score`, `quality_score`, `final_score`.
When the score is below the criterion's minimum, recommendations are stored in context under `self_evaluation_recommendations` for injection into backtrack prompts. Falls back to simple rule-based logic evaluation if no self-reported score is found.

### External LLM Evaluation

Configure a separate evaluator agent for independent quality assessment:

```java
var env = HensuFactory.builder()
    .agentProviders(List.of(new LangChain4jProvider()))
    .evaluatorAgent("evaluator")
    .build();

// Register the evaluator agent
env.getAgentRegistry().registerAgent("evaluator", AgentConfig.builder()
    .model("claude-sonnet-4-5-20250929")
    .role("Quality Evaluator")
    .build());
```

### Score-Based Routing

Nodes using rubrics can route based on evaluation scores via `ScoreTransition`:

```kotlin
onScore {
    whenScore greaterThanOrEqual 80.0 goto "approve"
    whenScore lessThan 80.0 goto "revise"
}
```

## Tool Registry

Protocol-agnostic tool descriptors used by plan generation and MCP integration. The core defines tool shapes; actual invocation happens through `ActionHandler` at the application layer.

### Registering Tools

```java
ToolRegistry registry = new DefaultToolRegistry();

// Simple tool (no parameters)
registry.register(ToolDefinition.simple("search", "Search the web"));

// Tool with parameters
registry.register(ToolDefinition.of("analyze", "Analyze data",
    List.of(
        ParameterDef.required("input", "string", "Data to analyze"),
        ParameterDef.optional("format", "string", "Output format", "json")
    )));
```

### MCP Integration

The server layer populates the tool registry from MCP server connections. Tools discovered via MCP become `ToolDefinition` instances available for plan generation and execution.

```
MCP Server ──► ToolDefinition ──► ToolRegistry ──► Planner ──► Plan
```

## Plan Engine

Supports static or LLM-generated step-by-step plan execution within nodes.

### Planning Modes

| Mode       | Description                                   |
|------------|-----------------------------------------------|
| `DISABLED` | No planning, direct agent execution (default) |
| `STATIC`   | Predefined plan from DSL `plan { }` block     |
| `DYNAMIC`  | LLM generates plan at runtime based on goal   |

### Static Plan Execution

```java
PlanExecutor executor = new PlanExecutor(actionExecutor);
executor.addObserver(event -> log.info("Event: " + event));

Plan plan = Plan.staticPlan("node", steps);
PlanResult result = executor.execute(plan, Map.of("orderId", "123"));

if (result.isSuccess()) {
    process(result.output());
}
```

### Observability

Plan execution emits events for monitoring:

| Event           | Description                        |
|-----------------|------------------------------------|
| `PlanCreated`   | Plan created and ready to execute  |
| `StepStarted`   | Individual step execution starting |
| `StepCompleted` | Step finished (success or failure) |
| `PlanCompleted` | All steps finished                 |

Register observers via `PlanExecutor.addObserver(PlanObserver)`.

## Template Resolution

The `TemplateResolver` substitutes `{variable}` placeholders in prompts from workflow context:

- `{topic}` — resolved from initial context or previous node output
- `{nodeId}` — resolved from the output of node with that ID
- `{param}` — resolved from extracted `outputParams`

## Human Review

Configure human review checkpoints via `ReviewHandler`:

```java
var env = HensuFactory.builder()
    .reviewHandler(new CLIReviewManager())
    .build();
```

Review modes per node:
- `DISABLED` — no human review (default)
- `OPTIONAL` — review only on failure
- `REQUIRED` — always require human review

## Testing

### Stub Mode

Run workflows without consuming API tokens using the built-in `StubAgentProvider`:

```bash
# Environment variable
export HENSU_STUB_ENABLED=true

# Or application property
hensu.stub.enabled=true
```

When enabled, `StubAgentProvider` (priority 1000) intercepts all model requests, returning mock responses.

### Unit Testing with Mock Providers

```java
@Test
void testWorkflowLogic() {
    AgentProvider mockProvider = new AgentProvider() {
        public String getName() { return "mock"; }
        public boolean supportsModel(String model) { return true; }
        public Agent createAgent(String id, AgentConfig config, Map<String, String> creds) {
            return new MockAgent(id, config);
        }
        public int getPriority() { return 100; }
    };

    var env = HensuFactory.builder()
        .agentProviders(List.of(mockProvider))
        .build();

    // Test workflow logic without real API calls
}
```

### Test Commands

```bash
./gradlew hensu-core:test                              # Run core tests
./gradlew hensu-core:test --tests "RubricEngineTest"   # Single test class
./gradlew test                                         # All modules
```

## GraalVM Native Image Constraints

`hensu-server` is deployed as a GraalVM native image. All code in `hensu-core` (and adapters) must be native-image safe. GraalVM performs static analysis at build time — anything not visible to the compiler at build time will not work at runtime.

### Rules

**No reflection.** GraalVM cannot discover classes, methods, or fields at runtime unless they are registered in advance. This drives several design decisions:

```java
// WRONG — reflection-based lookup fails in native image
Class<?> clazz = Class.forName("com.example.MyProvider");
Object instance = clazz.getDeclaredConstructor().newInstance();

// CORRECT — explicit construction
AgentProvider provider = new MyProvider();
```

**No classpath scanning.** Runtime scanning for classes (e.g., `ServiceLoader`, annotation scanning) doesn't work without build-time metadata. This is why providers are wired explicitly:

```java
// WRONG — ServiceLoader discovers via META-INF/services at runtime
ServiceLoader<AgentProvider> providers = ServiceLoader.load(AgentProvider.class);

// CORRECT — explicit provider list
HensuFactory.builder()
    .agentProviders(List.of(new LangChain4jProvider(), new MyProvider()))
    .build();
```

> **Note**: Quarkus *does* support `ServiceLoader` by processing `META-INF/services` at build time. But `hensu-core` avoids it entirely to remain framework-agnostic.

**No dynamic proxies without registration.** JDK dynamic proxies (`Proxy.newProxyInstance(...)`) and CGLIB require upfront registration. Prefer concrete classes or manual delegation over dynamic proxies.

**No runtime bytecode generation.** Libraries that generate classes at runtime (e.g., some serialization frameworks) fail silently. Use explicit, static implementations.

### Jackson Serialization Pattern

The `hensu-serialization` module uses explicit `SimpleModule` registrations instead of Jackson's reflective annotation processing:

```java
// In HensuJacksonModule — explicit serializer/deserializer registration
SimpleModule module = new SimpleModule("HensuModule");
module.addSerializer(Node.class, new NodeSerializer());
module.addDeserializer(Node.class, new NodeDeserializer());
module.addSerializer(TransitionRule.class, new TransitionRuleSerializer());
// ... each type explicitly registered
```

When adding new serializable types:
1. Create explicit `JsonSerializer<T>` and `JsonDeserializer<T>` classes
2. Register them in `HensuJacksonModule`
3. Do **not** rely on `@JsonTypeInfo` with class names — GraalVM cannot resolve them at runtime
4. Use a `"type"` discriminator field with an explicit `switch` in the deserializer

### Writing Native-Image-Safe Adapters

When implementing `AgentProvider` for a new AI backend:

1. **Avoid reflection in model builders.** If the upstream SDK uses `.builder()` patterns with reflection internally, the SDK's Quarkus extension (if available) registers the needed metadata. If no extension exists, you must provide `reflect-config.json`.

2. **Use `SimpleModule` for custom Jackson types.** Any new model or DTO must be explicitly serializable without `@JsonAutoDetect` or field-level reflection.

3. **Test with native image.** Run `./gradlew hensu-server:build -Dquarkus.native.enabled=true` to verify. Failures manifest as `ClassNotFoundException` or `NoSuchMethodException` at runtime.

### Quick Reference

| Pattern                              | Safe         | Unsafe                  |
|--------------------------------------|--------------|-------------------------|
| `new MyClass()`                      | Yes          | —                       |
| `Class.forName(...)`                 | —            | Yes                     |
| `field.setAccessible(true)`          | —            | Yes (unless registered) |
| `ServiceLoader.load(...)`            | Quarkus only | Yes (standalone)        |
| Jackson `SimpleModule`               | Yes          | —                       |
| Jackson `@JsonTypeInfo(use = CLASS)` | —            | Yes                     |
| `Proxy.newProxyInstance(...)`        | —            | Yes (unless registered) |
| Sealed interface `switch`            | Yes          | —                       |
| Builder pattern                      | Yes          | —                       |

## Credentials Management

Credentials are loaded via `HensuFactory` and passed to providers as a `Map<String, String>`.

### Loading Strategies

**From environment variables:**
```java
HensuFactory.builder()
    .loadCredentialsFromEnvironment()
    .build();
```

**From properties (`hensu.credentials.*` prefix, stripped automatically):**
```java
// application.properties:
// hensu.credentials.ANTHROPIC_API_KEY=sk-ant-...
// hensu.credentials.OPENAI_API_KEY=sk-...
// hensu.stub.enabled=true

HensuFactory.builder()
    .loadCredentials(properties)  // Loads from env + properties (properties win)
    .build();
```

**Individual keys:**
```java
HensuFactory.builder()
    .anthropicApiKey("sk-ant-...")
    .openAiApiKey("sk-...")
    .build();
```

### Supported Environment Variables

| Variable             | Provider         |
|----------------------|------------------|
| `ANTHROPIC_API_KEY`  | Anthropic Claude |
| `OPENAI_API_KEY`     | OpenAI GPT       |
| `GOOGLE_API_KEY`     | Google Gemini    |
| `DEEPSEEK_API_KEY`   | DeepSeek         |
| `OPENROUTER_API_KEY` | OpenRouter       |
| `AZURE_OPENAI_KEY`   | Azure OpenAI     |

Environment variables matching `*_API_KEY`, `*_KEY`, `*_SECRET`, or `*_TOKEN` patterns are auto-discovered.

## Key Files Reference

| File                                         | Description                                   |
|----------------------------------------------|-----------------------------------------------|
| `HensuFactory.java`                          | Bootstrap and environment creation            |
| `HensuEnvironment.java`                      | Container for all core components             |
| `HensuConfig.java`                           | Configuration (threading, storage)            |
| `agent/AgentFactory.java`                    | Creates agents from explicit providers        |
| `agent/spi/AgentProvider.java`               | Provider interface for pluggable AI backends  |
| `agent/AgentRegistry.java`                   | Agent lookup interface                        |
| `agent/DefaultAgentRegistry.java`            | Thread-safe agent registry                    |
| `agent/stub/StubAgentProvider.java`          | Testing provider (priority 1000 when enabled) |
| `execution/WorkflowExecutor.java`            | Main execution engine                         |
| `execution/executor/GenericNodeHandler.java` | Generic node handler interface                |
| `execution/action/ActionHandler.java`        | Action handler interface                      |
| `execution/action/ActionExecutor.java`       | Action dispatch interface                     |
| `execution/result/ExecutionResult.java`      | Workflow execution outcome                    |
| `workflow/Workflow.java`                     | Core data model                               |
| `rubric/RubricEngine.java`                   | Quality evaluation engine                     |
| `rubric/model/Rubric.java`                   | Rubric definition model                       |
| `tool/ToolDefinition.java`                   | Protocol-agnostic tool descriptor             |
| `tool/ToolRegistry.java`                     | Tool registration/lookup interface            |
| `plan/PlanExecutor.java`                     | Step-by-step plan execution                   |
| `plan/Plan.java`                             | Plan model (steps + constraints)              |
| `template/SimpleTemplateResolver.java`       | `{variable}` substitution                     |
| `review/ReviewHandler.java`                  | Human review interface                        |
| `state/ExecutionSnapshot.java`               | Serializable execution state                  |
