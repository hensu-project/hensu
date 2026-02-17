package io.hensu.core.agent.stub;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.spi.AgentProvider;
import java.util.Map;
import java.util.logging.Logger;

/// Agent provider that returns stub responses for testing without external API calls.
///
/// When enabled, intercepts ALL model requests with the highest priority (1000),
/// returning mock responses via {@link StubAgent}. Useful for testing workflow
/// logic, development, and CI/CD pipelines where API calls are undesirable.
///
/// ### Enabling Stub Mode
/// Enable via any of these methods (checked in order):
/// - Credentials map: `credentials.put("HENSU_STUB_ENABLED", "true")`
/// - System property: `-Dhensu.stub.enabled=true`
/// - Environment variable: `HENSU_STUB_ENABLED=true`
///
/// ### Priority Behavior
/// - When enabled: priority 1000 (intercepts all models)
/// - When disabled: priority -1 (never selected)
///
/// @implNote Thread-safe. Stateless provider that creates new {@link StubAgent}
/// instances for each request.
///
/// @see StubAgent for response generation logic
/// @see StubResponseRegistry for configuring stub responses
public class StubAgentProvider implements AgentProvider {

    private static final Logger logger = Logger.getLogger(StubAgentProvider.class.getName());

    private static final String ENABLED_KEY = "HENSU_STUB_ENABLED";
    private static final String ENABLED_PROPERTY = "hensu.stub.enabled";

    @Override
    public String getName() {
        return "stub";
    }

    /// Checks if this provider supports the given model.
    ///
    /// Returns `true` for all models when stub mode is enabled globally,
    /// allowing interception of any model request.
    ///
    /// @param modelName model identifier (ignored when enabled), not null
    /// @return `true` if stub mode is enabled globally, `false` otherwise
    @Override
    public boolean supportsModel(String modelName) {
        return isEnabledGlobally();
    }

    /// Creates a stub agent for the given configuration.
    ///
    /// @param agentId unique identifier for the agent, not null
    /// @param config agent configuration, not null
    /// @param credentials map containing optional `HENSU_STUB_ENABLED` override, not null
    /// @return stub agent instance, never null
    /// @throws IllegalStateException if called when stub mode is not enabled
    @Override
    public Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials) {
        if (!isEnabled(credentials)) {
            throw new IllegalStateException(
                    "Stub provider called but not enabled. This should not happen.");
        }

        logger.info(
                "[STUB] Creating stub agent: " + agentId + " (model: " + config.getModel() + ")");
        return new StubAgent(agentId, config);
    }

    /// Returns the provider priority.
    ///
    /// @return 1000 when enabled (highest priority), -1 when disabled
    @Override
    public int getPriority() {
        return isEnabledGlobally() ? 1000 : -1;
    }

    /// Checks if stub mode is enabled without credentials context.
    ///
    /// Checks system property and environment variable.
    ///
    /// @return `true` if enabled globally, `false` otherwise
    private boolean isEnabledGlobally() {
        String sysProp = System.getProperty(ENABLED_PROPERTY);
        if ("true".equalsIgnoreCase(sysProp)) {
            return true;
        }

        String envVar = System.getenv(ENABLED_KEY);
        return "true".equalsIgnoreCase(envVar);
    }

    /// Checks if stub mode is enabled with credentials context.
    ///
    /// Credentials map values take precedence over global settings,
    /// allowing per-execution override.
    ///
    /// @param credentials map to check for override values, may be null
    /// @return `true` if enabled, `false` otherwise
    private boolean isEnabled(Map<String, String> credentials) {
        if (credentials != null) {
            String credValue = credentials.get(ENABLED_KEY);
            if (credValue == null) {
                credValue = credentials.get(ENABLED_PROPERTY);
            }
            if ("true".equalsIgnoreCase(credValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(credValue)) {
                return false;
            }
        }

        return isEnabledGlobally();
    }
}
