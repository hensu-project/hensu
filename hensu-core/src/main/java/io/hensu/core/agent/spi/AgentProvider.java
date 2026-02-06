package io.hensu.core.agent.spi;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import java.util.List;
import java.util.Map;

/// Provider interface for pluggable agent implementations.
///
/// Implement this interface to add support for new AI providers (e.g., Claude, GPT,
/// custom models). Implementations are wired explicitly via
/// {@link io.hensu.core.agent.AgentFactory} constructor — no classpath scanning
/// or reflection is required, making this GraalVM native-image safe.
///
/// ### Registration
/// Pass provider instances to {@link io.hensu.core.HensuFactory.Builder#agentProviders(List)}:
/// {@snippet :
/// var env = HensuFactory.builder()
///     .agentProviders(List.of(new MyAgentProvider()))
///     .build();
/// }
///
/// ### Priority System
/// When multiple providers support the same model, the one with the highest
/// {@link #getPriority()} value is selected. Use this to:
/// - Override default implementations with custom ones
/// - Provide testing stubs that intercept all models
///
/// @implNote Implementations should be stateless and thread-safe.
/// The same provider instance may create agents for multiple workflows concurrently.
///
/// @see io.hensu.core.agent.AgentFactory for provider selection
/// @see io.hensu.core.agent.stub.StubAgentProvider for a testing implementation
public interface AgentProvider {

    /// Returns the provider's display name for logging and diagnostics.
    ///
    /// @return provider name (e.g., "langchain4j", "openai", "stub"), never null
    String getName();

    /// Checks if this provider can handle the specified model.
    ///
    /// Called during agent creation to find a suitable provider. Implementations
    /// should return `true` only if they can actually create agents for this model.
    ///
    /// @param modelName model identifier (e.g., "claude-sonnet-4", "gpt-4"), not null
    /// @return `true` if this provider can create agents for this model, `false` otherwise
    /// @throws NullPointerException if modelName is null
    boolean supportsModel(String modelName);

    /// Creates an agent instance for the specified configuration.
    ///
    /// Called after {@link #supportsModel(String)} returns `true`. The implementation
    /// should use the provided credentials to authenticate with the AI service.
    ///
    /// @param agentId unique identifier for the agent, not null
    /// @param config agent configuration (model, temperature, etc.), not null
    /// @param credentials map of API keys and other credentials, not null
    /// @return configured agent ready for execution, never null
    /// @throws NullPointerException if any parameter is null
    /// @throws IllegalStateException if required credentials are missing
    /// @throws IllegalArgumentException if configuration is invalid for this provider
    Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials);

    /// Returns this provider's priority for model selection.
    ///
    /// When multiple providers support the same model, the one with the highest
    /// priority is selected. Default implementations should use priority 0.
    /// Testing providers (like stub) typically use high priorities (e.g., 1000)
    /// to intercept all requests when enabled.
    ///
    /// @return priority value; higher values are preferred (default: 0)
    default int getPriority() {
        return 0;
    }
}
