package io.hensu.core.agent;

import io.hensu.core.agent.spi.AgentProvider;
import java.util.*;
import java.util.logging.Logger;

/// Factory for creating agents using discovered SPI providers.
///
/// Uses Java's {@link ServiceLoader} mechanism to discover {@link AgentProvider}
/// implementations at runtime. When creating an agent, selects the highest-priority
/// provider that supports the requested model.
///
/// ### Provider Discovery
/// Providers are loaded from `META-INF/services/io.hensu.core.agent.spi.AgentProvider`.
/// Multiple providers may support the same model; the one with the highest
/// {@link AgentProvider#getPriority()} value is selected.
///
/// @implNote Thread-safe after construction. Provider list and credentials are
/// immutable once the factory is created. Individual provider thread-safety
/// depends on the provider implementation.
///
/// @see AgentProvider for implementing custom agent backends
/// @see AgentRegistry for agent lifecycle management
public class AgentFactory {

    private static final Logger logger = Logger.getLogger(AgentFactory.class.getName());

    private final List<AgentProvider> providers;
    private final Map<String, String> credentials;

    /// Creates a new agent factory with the given credentials.
    ///
    /// Discovers all available {@link AgentProvider} implementations via ServiceLoader
    /// and logs the discovered providers at INFO level.
    ///
    /// @param credentials map of credential keys to values (e.g., `ANTHROPIC_API_KEY`), not null
    /// @throws NullPointerException if credentials is null
    public AgentFactory(Map<String, String> credentials) {
        this.credentials = new HashMap<>(credentials);
        this.providers = loadProviders();

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

    /// Loads all available providers via ServiceLoader.
    ///
    /// @return list of discovered providers, may be empty, never null
    private List<AgentProvider> loadProviders() {
        List<AgentProvider> discovered = new ArrayList<>();
        ServiceLoader<AgentProvider> loader = ServiceLoader.load(AgentProvider.class);

        for (AgentProvider provider : loader) {
            discovered.add(provider);
            logger.fine("Discovered provider: " + provider.getName());
        }

        return discovered;
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
