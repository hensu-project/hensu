# Hensu Developer Guide

This guide covers architecture details, API usage, adapter development, and testing strategies for developers working with or extending Hensu.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Module Structure](#module-structure)
- [Core Concepts](#core-concepts)
- [API Usage](#api-usage)
  - [Basic Usage](#basic-usage-core--langchain4j)
  - [Multiple Providers](#multiple-providers)
  - [Using HensuFactory.Builder](#using-hensufactorybuilder)
  - [Executing Workflows](#executing-workflows)
- [Creating Custom Adapters](#creating-custom-adapters)
- [Generic Nodes](#generic-nodes)
- [Testing](#testing)
- [Build System](#build-system)
- [Credentials Management](#credentials-management)

## Architecture Overview

Hensu uses a **modular adapter pattern** to keep the core library free from AI dependencies. Users can choose which AI providers they want by including the appropriate adapter modules.

```
hensu-parent/
├── hensu-core                    # Core workflow engine (NO AI dependencies)
├── hensu-cli                     # Quarkus-based CLI (PicoCLI)
├── hensu-langchain4j-adapter     # LangChain4j integration (Claude, GPT, Gemini)
└── hensu-openrouter-adapter      # OpenRouter integration (future)
```

**Dependency flow**: `cli → core ← langchain4j-adapter`

## Module Structure

### hensu-core

Workflow engine containing:

- **Entry Point**: `HensuFactory.createEnvironment()` bootstraps everything
- **HensuEnvironment** - Container holding all core components
- **AgentFactory** - Discovers AI providers via ServiceLoader
- **AgentRegistry / DefaultAgentRegistry** - Manages agent instances
- **WorkflowExecutor** - Executes workflow graphs
- **NodeExecutorRegistry** - Registry for node type executors (Standard, Loop, Fork, Join, etc.)
- **RubricEngine** - Evaluates output quality using RubricRepository and RubricEvaluator
- **TemplateResolver** - Resolves `{placeholder}` syntax in prompts
- **ReviewHandler** - Handles human review checkpoints (optional)
- **ActionExecutor** - Executes workflow actions like notifications, commands, HTTP calls (optional)

### hensu-cli

Quarkus-based CLI application with PicoCLI commands:

- `run` - Execute workflows
- `validate` - Validate workflow syntax
- `visualize` - Render workflow graphs

### hensu-langchain4j-adapter

LangChain4j integration supporting:

- Anthropic Claude models
- OpenAI GPT models
- Google Gemini models
- DeepSeek models

## Core Concepts

### 1. Service Provider Interface (SPI)

The core module defines an `AgentProvider` interface using Java's ServiceLoader mechanism:

```java
public interface AgentProvider {
    String getName();
    boolean supportsModel(String modelName);
    Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials);
    int getPriority();
}
```

### 2. Automatic Discovery

Adapters register themselves via ServiceLoader:

```
hensu-langchain4j-adapter/src/main/resources/
└── META-INF/
    └── services/
        └── io.hensu.core.agent.spi.AgentProvider
            (contains: io.hensu.adapter.langchain4j.LangChain4jProvider)
```

### 3. Dynamic Loading

At runtime, `AgentFactory` discovers all available providers:

```java
AgentFactory factory = new AgentFactory(credentials);
// Automatically finds: LangChain4jProvider, OpenRouterProvider, etc.
```

### 4. Provider Priority

When multiple providers support the same model, higher priority wins:

```java
public class LangChain4jProvider implements AgentProvider {
    public int getPriority() { return 100; }  // Highest priority
}

public class OpenRouterProvider implements AgentProvider {
    public int getPriority() { return 50; }   // Fallback
}

public class MockProvider implements AgentProvider {
    public int getPriority() { return 0; }    // Lowest (test only)
}
```

## API Usage

### Basic Usage (Core + LangChain4j)

**build.gradle.kts**
```kotlin
dependencies {
    implementation("io.hensu:hensu-core:1.0.0-SNAPSHOT")
    implementation("io.hensu:hensu-langchain4j-adapter:1.0.0-SNAPSHOT")
}
```

**Application Code**
```java
Map<String, String> credentials = new HashMap<>();
credentials.put("ANTHROPIC_API_KEY", System.getenv("ANTHROPIC_API_KEY"));
credentials.put("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));

// AgentFactory automatically discovers LangChain4jProvider
AgentFactory factory = new AgentFactory(credentials);
DefaultAgentRegistry registry = new DefaultAgentRegistry(factory);

// Register agents - they'll use LangChain4j automatically
registry.registerAgent("reviewer", AgentConfig.builder()
    .model("claude-sonnet-4-20250514")
    .role("Code Reviewer")
    .build());

registry.registerAgent("fixer", AgentConfig.builder()
    .model("gpt-4")
    .role("Code Fixer")
    .build());

// Use in workflow
WorkflowExecutor executor = new WorkflowExecutor(registry, ...);
```

### Multiple Providers

```kotlin
dependencies {
    implementation("io.hensu:hensu-core:1.0.0-SNAPSHOT")
    implementation("io.hensu:hensu-langchain4j-adapter:1.0.0-SNAPSHOT")
    implementation("io.hensu:hensu-openrouter-adapter:1.0.0-SNAPSHOT")
}
```

```java
AgentFactory factory = new AgentFactory(credentials);

// All providers are available automatically
System.out.println(factory.getProviders());
// Output: [LangChain4jProvider, OpenRouterProvider]

// Use Claude via LangChain4j
registry.registerAgent("claude", AgentConfig.builder()
    .model("claude-sonnet-4-20250514")
    .build());

// Use Llama via OpenRouter
registry.registerAgent("llama", AgentConfig.builder()
    .model("meta-llama/llama-3-70b")
    .build());
```

### Using HensuFactory.Builder

For fine-grained control, use the fluent builder:

```java
HensuEnvironment env = HensuFactory.builder()
    .anthropicApiKey("sk-ant-...")
    .openAiApiKey("sk-...")
    .config(HensuConfig.builder()
        .useVirtualThreads(true)
        .build())
    .stubMode(false)  // Enable for testing without API calls
    .evaluatorAgent("evaluator")  // Optional: LLM-based rubric evaluation
    .reviewHandler(myReviewHandler)  // Optional: human review support
    .actionExecutor(myActionExecutor)  // Optional: action execution
    .build();
```

Or load credentials from environment automatically:

```java
HensuEnvironment env = HensuFactory.builder()
    .loadCredentialsFromEnvironment()
    .config(HensuConfig.builder().useVirtualThreads(true).build())
    .build();
```

### Executing Workflows

Once you have a `HensuEnvironment`, execute workflows via the `WorkflowExecutor`:

```java
// Get executor from environment
HensuEnvironment env = HensuFactory.createEnvironment();
WorkflowExecutor executor = env.getWorkflowExecutor();

// Parse and execute a workflow
KotlinScriptParser parser = new KotlinScriptParser(workingDir);
Workflow workflow = parser.parse(Path.of("my-workflow.kt"));

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
    implementation("io.hensu:hensu-core:1.0.0-SNAPSHOT")
    implementation("com.myai:myai-sdk:1.0.0")
}
```

### 2. Implement AgentProvider

```java
package io.hensu.adapter.myai;

import io.hensu.core.agent.spi.AgentProvider;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;

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
import io.hensu.core.agent.AgentResponse;

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

### 4. Register via ServiceLoader

Create file: `src/main/resources/META-INF/services/io.hensu.core.agent.spi.AgentProvider`

Content:
```
io.hensu.adapter.myai.MyAiProvider
```

### 5. Use It

```kotlin
dependencies {
    implementation("io.hensu:hensu-myai-adapter:1.0.0-SNAPSHOT")
}
```

```java
// Automatically discovered and used!
registry.registerAgent("myagent", AgentConfig.builder()
    .model("myai-turbo")
    .build());
```

## Generic Nodes

Generic nodes allow you to define custom execution logic without involving an AI agent. They're useful for:

- Data validation
- Data transformation
- External service integration
- Conditional branching logic
- Any arbitrary computation

### How Generic Nodes Work

1. Define a `GenericNode` in your workflow DSL with an `executorType`
2. Create a `GenericNodeHandler` implementation with matching type
3. Register the handler with `NodeExecutorRegistry`
4. At runtime, `GenericNodeExecutor` looks up your handler and invokes it

### DSL Usage

```kotlin
graph {
    start at "validate"

    generic("validate") {
        executorType = "validator"
        config {
            "field" to "user_input"
            "required" to true
            "minLength" to 10
            "maxLength" to 1000
        }
        onSuccess goto "transform"
        onFailure retry 0 otherwise "error"
    }

    generic("transform") {
        executorType = "data-transformer"
        config {
            "inputField" to "user_input"
            "outputField" to "processed_input"
            "operations" to listOf("trim", "lowercase", "normalize")
        }
        onSuccess goto "process"
    }

    node("process") {
        agent = "processor"
        prompt = "Process: {processed_input}"
        onSuccess goto "end"
    }

    end("end")
    end("error", ExitStatus.FAILURE)
}
```

### Creating a Handler

Implement the `GenericNodeHandler` interface:

```java
package com.example.handlers;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.GenericNodeHandler;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.workflow.node.GenericNode;
import java.util.Map;

public class MyCustomHandler implements GenericNodeHandler {

    public static final String TYPE = "my-custom-handler";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) throws Exception {
        // Access node configuration
        Map<String, Object> config = node.getConfig();
        String param = (String) config.getOrDefault("myParam", "default");

        // Access workflow state/context
        Map<String, Object> state = context.getState().getContext();
        Object input = state.get("input");

        // Perform your custom logic
        String result = processData(input, param);

        // Store results back in context for subsequent nodes
        state.put("my_output", result);

        // Return success with output and metadata
        return NodeResult.success(result, Map.of(
            "processed", true,
            "param_used", param
        ));

        // Or return failure
        // return NodeResult.failure("Error message");
    }

    private String processData(Object input, String param) {
        // Your logic here
        return input != null ? input.toString().toUpperCase() : "";
    }
}
```

### Registering Handlers

#### Manual Registration (Pure Java)

```java
// Get the registry from environment
HensuEnvironment env = HensuFactory.createEnvironment();
NodeExecutorRegistry registry = env.getNodeExecutorRegistry();

// Register your handler
registry.registerGenericHandler("my-custom-handler", new MyCustomHandler());
registry.registerGenericHandler("validator", new ValidatorHandler());
registry.registerGenericHandler("data-transformer", new DataTransformerHandler());
```

#### CDI Auto-Discovery (Quarkus/CDI)

With CDI, handlers annotated with `@ApplicationScoped` are automatically discovered and registered:

```java
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ValidatorHandler implements GenericNodeHandler {

    public static final String TYPE = "validator";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public NodeResult handle(GenericNode node, ExecutionContext context) {
        Map<String, Object> config = node.getConfig();
        String fieldName = (String) config.getOrDefault("field", "input");
        Object fieldValue = context.getState().getContext().get(fieldName);

        // Validation logic...
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

The CLI module registers all discovered handlers automatically via CDI injection:

```java
@Inject Instance<GenericNodeHandler> genericNodeHandlers;

private void registerGenericHandlers() {
    for (GenericNodeHandler handler : genericNodeHandlers) {
        hensuEnvironment.getNodeExecutorRegistry()
            .registerGenericHandler(handler.getType(), handler);
    }
}
```

### Built-in Handlers (CLI Module)

The CLI module provides these ready-to-use handlers:

| Handler Type | Class | Description |
|--------------|-------|-------------|
| `validator` | `ValidatorHandler` | Validates fields (required, minLength, maxLength, pattern) |
| `data-transformer` | `DataTransformerHandler` | Transforms strings (trim, lowercase, uppercase, normalize) |
| `api-caller` | `ApiCallerHandler` | Makes HTTP API calls |

### Handler Best Practices

1. **Use descriptive type names**: `"user-validator"` is better than `"v1"`
2. **Access config safely**: Use `getOrDefault()` for optional parameters
3. **Store outputs in context**: Put results in `context.getState().getContext()` for subsequent nodes
4. **Return meaningful metadata**: Include relevant info in `NodeResult` metadata map
5. **Handle errors gracefully**: Return `NodeResult.failure()` with clear error messages

## Testing

### Stub Mode (No API Tokens)

Run workflows without consuming API tokens using the built-in stub provider.

**Enable via application.properties:**
```properties
hensu.stub.enabled=true
```

**Or via environment variable:**
```bash
export HENSU_STUB_ENABLED=true
./hensu run workflow.kt -v
```

**Or via system property:**
```bash
java -Dhensu.stub.enabled=true -jar hensu-cli/build/quarkus-app/quarkus-run.jar run workflow.kt
```

When enabled, stub agents return mock responses:
```
Creating agent 'researcher' with provider: stub
[STUB] Creating stub agent: researcher (model: claude-sonnet-4-5)
```

### Testing Without AI Dependencies

**build.gradle.kts** (test scope only)
```kotlin
dependencies {
    implementation("io.hensu:hensu-core:1.0.0-SNAPSHOT")
    // NO AI adapters! Use mocks instead
}
```

**Test Code**
```java
@Test
void testWorkflowLogic() {
    // Create mock provider for testing
    AgentProvider mockProvider = new AgentProvider() {
        public String getName() { return "mock"; }
        public boolean supportsModel(String model) { return true; }
        public Agent createAgent(String id, AgentConfig config, Map<String, String> creds) {
            return new MockAgent(id, config);
        }
    };

    // Test workflow logic without hitting real APIs
    AgentFactory factory = createFactoryWithProvider(mockProvider);
    // ... test your workflow
}
```

### Test Commands

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew hensu-core:test

# Run a single test class
./gradlew hensu-core:test --tests "RubricEngineTest"
```

## Build System

```bash
# Build everything
./gradlew build

# Build only core (no AI dependencies)
./gradlew hensu-core:build

# Build specific module
./gradlew hensu-langchain4j-adapter:build

# Run tests
./gradlew test

# Skip tests
./gradlew build -x test

# Run CLI in dev mode
./gradlew hensu-cli:quarkusDev
```

### Using Make

```bash
make build                              # Build all modules
make test                               # Run all tests
make run                                # Run default workflow
make run-verbose                        # Run with agent I/O output
make run WORKFLOW=my-workflow.kt        # Run specific workflow
make validate                           # Validate workflow syntax
make visualize                          # Visualize as ASCII text
make visualize-mermaid                  # Visualize as Mermaid diagram
make dev                                # Run in Quarkus dev mode
make clean                              # Clean build artifacts
```

## Credentials Management

Credentials are passed as a Map to AgentFactory:

```java
Map<String, String> credentials = new HashMap<>();

// Standard names (automatically detected)
credentials.put("ANTHROPIC_API_KEY", "sk-ant-...");
credentials.put("OPENAI_API_KEY", "sk-...");

// Custom provider credentials
credentials.put("MYAI_API_KEY", "...");

AgentFactory factory = new AgentFactory(credentials);
```

### Supported Environment Variables

| Variable | Provider |
|----------|----------|
| `ANTHROPIC_API_KEY` | Anthropic Claude |
| `OPENAI_API_KEY` | OpenAI GPT |
| `GOOGLE_API_KEY` | Google Gemini |
| `DEEPSEEK_API_KEY` | DeepSeek |
| `OPENROUTER_API_KEY` | OpenRouter |
| `AZURE_OPENAI_KEY` | Azure OpenAI |

Adapters look for their specific keys automatically.

## Key Files Reference

| File | Description |
|------|-------------|
| `hensu-core/.../HensuFactory.java` | Bootstrap and environment creation |
| `hensu-core/.../agent/AgentFactory.java` | ServiceLoader discovery |
| `hensu-core/.../execution/WorkflowExecutor.java` | Main execution engine |
| `hensu-core/.../workflow/Workflow.java` | Core data model |
| `hensu-core/.../dsl/HensuDSL.kt` | DSL entry point (`workflow()` function) |
| `hensu-core/.../dsl/parsers/KotlinScriptParser.kt` | Script compilation |

## FAQ

**Q: Do I need to manually register providers?**
A: No! ServiceLoader discovers them automatically.

**Q: Can I use multiple adapters together?**
A: Yes! They all work together seamlessly.

**Q: How do I test without hitting real APIs?**
A: Enable stub mode with `hensu.stub.enabled=true` or `HENSU_STUB_ENABLED=true`. Alternatively, don't include any adapter dependencies in tests and create mock providers.

**Q: Can I create my own adapter?**
A: Yes! Implement `AgentProvider` and register via ServiceLoader.

**Q: What if no provider supports my model?**
A: AgentFactory throws `IllegalStateException` with a helpful error message.

**Q: How does priority work?**
A: Higher priority = preferred. Used when multiple providers support the same model.