package io.hensu.core;

import io.hensu.core.agent.AgentFactory;
import io.hensu.core.agent.AgentProvider;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.DefaultAgentRegistry;
import io.hensu.core.agent.stub.StubAgentProvider;
import io.hensu.core.execution.NodeLifecycleCoordinator;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.pipeline.ProcessorPipeline;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.rubric.InMemoryRubricRepository;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricRepository;
import io.hensu.core.rubric.evaluator.ScoreExtractingEvaluator;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.InMemoryWorkflowRepository;
import io.hensu.core.workflow.WorkflowRepository;
import java.util.*;

/// Factory for creating and wiring Hensu workflow execution environments.
///
/// Every {@link HensuEnvironment} is built through the fluent {@link Builder}, which handles
/// dependency wiring, credential loading, and component initialization with zero external
/// dependencies.
///
/// ### Usage
/// {@snippet :
/// var env = HensuFactory.builder()
///     .loadCredentials(properties)
///     .agentProviders(List.of(new LangChain4jProvider()))
///     .build();
/// }
///
/// @see HensuEnvironment
/// @see Builder
public final class HensuFactory {

    private static final String CREDENTIALS_PREFIX = "hensu.credentials.";
    private static final String STUB_ENABLED_KEY = "hensu.stub.enabled";

    private HensuFactory() {
        // Utility class - prevent instantiation
    }

    /// Discovers API credentials in environment variables.
    ///
    /// @return map of discovered credentials, never null (may be empty)
    /// @see Builder#loadCredentialsFromEnvironment()
    private static Map<String, String> credentialsFromEnvironment() {
        Map<String, String> credentials = new HashMap<>();

        // Auto-discover all environment variables matching API key patterns
        System.getenv()
                .forEach(
                        (key, value) -> {
                            if (value != null && !value.isEmpty() && isApiKeyPattern(key)) {
                                credentials.put(key, value);
                            }
                        });

        return credentials;
    }

    /// Checks if an environment variable name matches common API key patterns.
    ///
    /// @param key the environment variable name, not null
    /// @return `true` if the key matches an API key pattern
    private static boolean isApiKeyPattern(String key) {
        String upperKey = key.toUpperCase();
        return upperKey.endsWith("_API_KEY")
                || upperKey.endsWith("_KEY")
                || upperKey.endsWith("_SECRET")
                || upperKey.endsWith("_TOKEN");
    }

    /// Extracts credentials from a Properties object.
    ///
    /// Supports multiple patterns:
    /// - Prefixed keys (e.g., `hensu.credentials.ANTHROPIC_API_KEY=sk-...`) — prefix is stripped
    /// - Direct API key names (e.g., `ANTHROPIC_API_KEY=sk-...`)
    /// - Stub mode setting: `hensu.stub.enabled=true`
    ///
    /// @param properties the properties to extract credentials from, not null
    /// @return map of credential keys to their values, never null (may be empty)
    private static Map<String, String> credentialsFromProperties(Properties properties) {
        Map<String, String> credentials = new HashMap<>();

        properties.forEach(
                (key, value) -> {
                    String keyStr = key.toString();
                    String valueStr = value.toString();

                    if (valueStr != null && !valueStr.isEmpty()) {
                        // Check for prefixed keys (hensu.credentials.ANTHROPIC_API_KEY)
                        if (keyStr.startsWith(CREDENTIALS_PREFIX)) {
                            String credentialKey = keyStr.substring(CREDENTIALS_PREFIX.length());
                            credentials.put(credentialKey, valueStr);
                        }
                        // Check for stub mode setting
                        else if (keyStr.equals(STUB_ENABLED_KEY)) {
                            credentials.put(STUB_ENABLED_KEY, valueStr);
                        }
                        // Also check for direct API key patterns (ANTHROPIC_API_KEY)
                        else if (isApiKeyPattern(keyStr)) {
                            credentials.put(keyStr, valueStr);
                        }
                    }
                });

        return credentials;
    }

    /// Fluent builder for constructing {@link HensuEnvironment} instances with fine-grained
    /// control.
    ///
    /// Provides methods for configuring credentials, agent providers, registries,
    /// handlers, and tools. Call {@link #build()} to create the environment.
    ///
    /// ### Example
    /// {@snippet :
    /// var env = HensuFactory.builder()
    ///     .loadCredentials(properties)
    ///     .agentProviders(List.of(new LangChain4jProvider()))
    ///     .actionExecutor(actionExecutor)
    ///     .build();
    /// }
    ///
    /// @implNote **Not thread-safe**. Intended for single-threaded configuration
    /// before calling {@link #build()}.
    public static class Builder {
        private Map<String, String> credentials = new HashMap<>();
        private List<AgentProvider> agentProviders = new ArrayList<>();
        private NodeExecutorRegistry nodeExecutorRegistry;
        private AgentRegistry agentRegistry;
        private ReviewHandler reviewHandler = null;
        private ActionExecutor actionExecutor = null;
        private WorkflowRepository workflowRepository;
        private ToolRouter toolRouter;

        /// Adds a single credential key-value pair.
        ///
        /// @param key the credential key (e.g., `ANTHROPIC_API_KEY`), not null
        /// @param value the credential value, not null
        /// @return this builder for chaining, never null
        public Builder credential(String key, String value) {
            this.credentials.put(key, value);
            return this;
        }

        /// Sets the agent providers for model creation.
        ///
        /// Providers are selected by priority when creating agents. The built-in
        /// {@link StubAgentProvider} is always included automatically — do not add
        /// it explicitly.
        ///
        /// @param providers list of agent providers, not null
        /// @return this builder for chaining, never null
        /// @see AgentProvider for implementing custom providers
        public Builder agentProviders(List<AgentProvider> providers) {
            this.agentProviders = new ArrayList<>(providers);
            return this;
        }

        /// Enables or disables stub mode for testing without API calls.
        ///
        /// When enabled, every agent resolves to a stub agent that returns mock responses,
        /// allowing workflow testing without consuming API tokens. The setting applies to
        /// this environment only and overrides the `hensu.stub.enabled` system property and
        /// `HENSU_STUB_ENABLED` environment variable. Agents declaring the model `stub` are
        /// stubbed either way.
        ///
        /// @param enabled `true` to enable stub mode, `false` to disable it even when the
        ///     system property or environment variable is set
        /// @return this builder for chaining, never null
        public Builder stubMode(boolean enabled) {
            this.credentials.put(STUB_ENABLED_KEY, String.valueOf(enabled));
            return this;
        }

        /// Configures a handler for human review checkpoints.
        ///
        /// When set, workflow nodes with review configuration invoke this handler
        /// to request human approval, backtracking, or rejection.
        ///
        /// @param reviewHandler the review handler, may be null for auto-approve behavior
        /// @return this builder for chaining, never null
        public Builder reviewHandler(ReviewHandler reviewHandler) {
            this.reviewHandler = reviewHandler;
            return this;
        }

        /// Configures an executor for action node.
        ///
        /// When set, actions (send, execute) are processed
        /// by this executor. If not set, actions are logged but not executed.
        ///
        /// @param actionExecutor the action executor, may be null for logging-only mode
        /// @return this builder for chaining, never null
        public Builder actionExecutor(ActionExecutor actionExecutor) {
            this.actionExecutor = actionExecutor;
            return this;
        }

        /// Sets a custom workflow repository for tenant-scoped workflow storage.
        ///
        /// When not set, defaults to {@link InMemoryWorkflowRepository}.
        ///
        /// @param workflowRepository the repository, may be null for default in-memory storage
        /// @return this builder for chaining, never null
        public Builder workflowRepository(WorkflowRepository workflowRepository) {
            this.workflowRepository = workflowRepository;
            return this;
        }

        /// Sets a custom node executor registry.
        ///
        /// @param nodeExecutorRegistry the registry, may be null for default
        /// @return this builder for chaining, never null
        public Builder nodeExecutorRegistry(NodeExecutorRegistry nodeExecutorRegistry) {
            this.nodeExecutorRegistry = nodeExecutorRegistry;
            return this;
        }

        /// Sets a custom agent registry.
        ///
        /// @param agentRegistry the registry, may be null for default
        /// @return this builder for chaining, never null
        public Builder agentRegistry(AgentRegistry agentRegistry) {
            this.agentRegistry = agentRegistry;
            return this;
        }

        /// Loads credentials from environment variables matching API key patterns.
        ///
        /// Automatically discovers API keys matching common naming patterns:
        /// - `*_API_KEY` (e.g., `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`)
        /// - `*_KEY` (e.g., `AZURE_OPENAI_KEY`)
        /// - `*_SECRET` (e.g., `CLIENT_SECRET`)
        /// - `*_TOKEN` (e.g., `ACCESS_TOKEN`)
        ///
        /// @return this builder for chaining, never null
        public Builder loadCredentialsFromEnvironment() {
            this.credentials.putAll(credentialsFromEnvironment());
            return this;
        }

        /// Loads credentials from environment variables and properties.
        ///
        /// Properties take precedence over environment variables. Supports
        /// `hensu.credentials.*` prefixed keys (prefix is stripped), direct API key
        /// patterns, and `hensu.stub.enabled`.
        ///
        /// @param properties the properties to merge with environment credentials, not null
        /// @return this builder for chaining, never null
        public Builder loadCredentials(Properties properties) {
            this.credentials.putAll(credentialsFromEnvironment());
            this.credentials.putAll(credentialsFromProperties(properties));
            return this;
        }

        /// Configures the tool router backing agent tool discovery and invocation.
        ///
        /// Defaults to {@link ToolRouter#empty()} when not set, which exposes no
        /// tools; a runtime enables the tool loop by composing its own
        /// {@link io.hensu.core.tool.ToolProvider} instances into a router.
        ///
        /// @param toolRouter the tool router, not null
        /// @return this builder for chaining, never null
        public Builder toolRouter(ToolRouter toolRouter) {
            this.toolRouter = toolRouter;
            return this;
        }

        /// Builds and returns the configured {@link HensuEnvironment}.
        ///
        /// Constructs all runtime components (rubric engine, template resolver, workflow
        /// executor) and assembles the final environment.
        ///
        /// ### Contracts
        /// - **Postcondition**: Returns a fully-initialized environment
        ///
        /// @apiNote **Side effects**:
        /// - Auto-loads environment credentials if none were explicitly provided
        ///
        /// @return the configured environment, never null
        public HensuEnvironment build() {
            // Autoload from environment if no explicit credentials provided
            if (credentials.isEmpty()) {
                credentials = credentialsFromEnvironment();
            }
            if (agentRegistry == null) {
                // Always include StubAgentProvider alongside explicit providers
                List<AgentProvider> allProviders = new ArrayList<>(agentProviders);
                allProviders.add(StubAgentProvider.fromCredentials(credentials));
                AgentFactory agentFactory = new AgentFactory(credentials, allProviders);
                agentRegistry = new DefaultAgentRegistry(agentFactory);
            }
            if (nodeExecutorRegistry == null) {
                nodeExecutorRegistry = new DefaultNodeExecutorRegistry();
            }
            if (workflowRepository == null) {
                workflowRepository = new InMemoryWorkflowRepository();
            }

            ReviewHandler effectiveReviewHandler =
                    reviewHandler != null ? reviewHandler : ReviewHandler.AUTO_APPROVE;
            RubricRepository rubricRepository = new InMemoryRubricRepository();
            RubricEngine rubricEngine =
                    new RubricEngine(rubricRepository, new ScoreExtractingEvaluator());

            NodeLifecycleCoordinator lifecycleCoordinator =
                    new NodeLifecycleCoordinator(
                            nodeExecutorRegistry,
                            ProcessorPipeline.preExecution(),
                            ProcessorPipeline.postExecution(effectiveReviewHandler, rubricEngine));

            WorkflowExecutor workflowExecutor =
                    new WorkflowExecutor(
                            nodeExecutorRegistry,
                            agentRegistry,
                            rubricEngine,
                            lifecycleCoordinator,
                            actionExecutor,
                            new SimpleTemplateResolver(),
                            workflowRepository,
                            toolRouter != null ? toolRouter : ToolRouter.empty());

            return new HensuEnvironment(
                    workflowExecutor,
                    nodeExecutorRegistry,
                    agentRegistry,
                    rubricRepository,
                    actionExecutor,
                    workflowRepository);
        }
    }

    /// Creates a new builder for fluent environment configuration.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }
}
