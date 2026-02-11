package io.hensu.core.agent;

import java.util.*;
import java.util.logging.Logger;

/// Factory for creating agents from explicitly provided {@link AgentProvider} instances.
///
/// Selects the highest-priority provider that supports the requested model.
/// {@link io.hensu.core.agent.stub.StubAgentProvider} uses priority 1000 when
/// enabled, intercepting all model requests for testing.
///
/// Providers are wired explicitly via constructor. This ensures reliable behavior
/// in GraalVM native image contexts.
///
/// @implNote Thread-safe after construction. Provider list and credentials are
/// immutable once the factory is created.
///
/// @see AgentProvider for implementing custom agent backends
/// @see AgentRegistry for agent lifecycle management
public class AgentFactory {

    private static final Logger logger = Logger.getLogger(AgentFactory.class.getName());

    private final List<AgentProvider> providers;
    private final Map<String, String> credentials;

    /// Creates a new agent factory with the given providers.
    ///
    /// @param credentials map of credential keys to values (e.g., `ANTHROPIC_API_KEY`), not null
    /// @param providers list of agent providers to use for model creation, not null
    /// @throws NullPointerException if credentials or providers is null
    public AgentFactory(Map<String, String> credentials, List<AgentProvider> providers) {
        this.credentials = new HashMap<>(credentials);
        this.providers = new ArrayList<>(providers);

        logger.info(
                "Loaded "
                        + providers.size()
                        + " agent providers: "
                        + providers.stream().map(AgentProvider::getName).toList());
    }

    /// Creates an agent using the appropriate provider for the configured model.
    ///
    /// Selects the provider with the highest priority that supports the model
    /// specified in the configuration.
    ///
    /// @param agentId unique identifier for the agent, not null
    /// @param config agent configuration specifying model and parameters, not null
    /// @return the created agent, never null
    /// @throws NullPointerException if agentId or config is null
    /// @throws IllegalStateException if no provider supports the configured model
    public Agent createAgent(String agentId, AgentConfig config) {
        String modelName = config.getModel();

        AgentProvider provider =
                providers.stream()
                        .filter(p -> p.supportsModel(modelName))
                        .max(Comparator.comparingInt(AgentProvider::getPriority))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No provider found for model: "
                                                        + modelName
                                                        + ". Available providers: "
                                                        + providers.stream()
                                                                .map(AgentProvider::getName)
                                                                .toList()));

        logger.info("Creating agent '" + agentId + "' with provider: " + provider.getName());
        return provider.createAgent(agentId, config, credentials);
    }

    /// Returns an unmodifiable view of all loaded providers.
    ///
    /// @return list of available providers, never null
    public List<AgentProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /// Checks if any loaded provider supports the given model.
    ///
    /// @param modelName the model identifier to check (e.g., `claude-sonnet-4`), not null
    /// @return `true` if at least one provider supports this model, `false` otherwise
    /// @throws NullPointerException if modelName is null
    public boolean isModelSupported(String modelName) {
        return providers.stream().anyMatch(p -> p.supportsModel(modelName));
    }
}
