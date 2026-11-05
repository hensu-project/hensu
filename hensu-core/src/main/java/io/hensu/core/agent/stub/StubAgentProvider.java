package io.hensu.core.agent.stub;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentProvider;
import java.util.Map;
import java.util.logging.Logger;

/// Agent provider that returns stub responses for testing without external API calls.
///
/// Returns mock responses via {@link StubAgent}. Useful for testing workflow logic,
/// development, and CI/CD pipelines where API calls are undesirable.
///
/// ### Selection
/// The provider claims models in one of two ways:
/// - **By name** – an agent declaring the model {@value #MODEL} is stubbed regardless of the
///   stub mode switch, even when it is explicitly `false`. Other agents in the same workflow
///   keep their real providers, so a stubbed writer and a live reviewer can run in one
///   execution.
/// - **Stub mode** – when the switch is on, the provider claims every model at priority 1000,
///   outranking all real providers. This is the "stub everything" escape hatch.
///
/// ### Enabling Stub Mode
/// The switch is resolved once, when the environment is built, in this order:
/// - Credentials map: `hensu.stub.enabled` or `HENSU_STUB_ENABLED` – an explicit `true` or
///   `false` wins over the global settings, so it scopes the switch to one environment
/// - System property: `-Dhensu.stub.enabled=true`
/// - Environment variable: `HENSU_STUB_ENABLED=true`
///
/// @implNote Thread-safe. Immutable after construction; creates new {@link StubAgent}
/// instances for each request.
///
/// @see StubAgent for response generation logic
/// @see StubResponseRegistry for configuring stub responses
public class StubAgentProvider implements AgentProvider {

    /// Model name that selects this provider regardless of stub mode.
    public static final String MODEL = "stub";

    private static final Logger logger = Logger.getLogger(StubAgentProvider.class.getName());

    private static final String ENABLED_KEY = "HENSU_STUB_ENABLED";
    private static final String ENABLED_PROPERTY = "hensu.stub.enabled";
    private static final int STUB_MODE_PRIORITY = 1000;

    private final boolean interceptAll;

    /// Creates a stub provider.
    ///
    /// @param interceptAll `true` to claim every model (stub mode), `false` to claim only
    ///     {@value #MODEL}
    public StubAgentProvider(boolean interceptAll) {
        this.interceptAll = interceptAll;
    }

    /// Creates a stub provider whose stub mode is resolved from the given credentials,
    /// falling back to the system property and environment variable.
    ///
    /// @param credentials map possibly containing `hensu.stub.enabled` or `HENSU_STUB_ENABLED`,
    ///     not null
    /// @return stub provider, never null
    public static StubAgentProvider fromCredentials(Map<String, String> credentials) {
        return new StubAgentProvider(isEnabled(credentials));
    }

    @Override
    public String getName() {
        return "stub";
    }

    /// Checks if this provider supports the given model.
    ///
    /// @param modelName model identifier, not null
    /// @return `true` if the model is {@value #MODEL} or stub mode is on, `false` otherwise
    @Override
    public boolean supportsModel(String modelName) {
        return interceptAll || MODEL.equals(modelName);
    }

    @Override
    public String supportedModels() {
        return interceptAll ? "any model (stub mode enabled)" : MODEL;
    }

    /// Creates a stub agent for the given configuration.
    ///
    /// @param agentId unique identifier for the agent, not null
    /// @param config agent configuration, not null
    /// @param credentials ignored, not null
    /// @return stub agent instance, never null
    @Override
    public Agent createAgent(String agentId, AgentConfig config, Map<String, String> credentials) {
        logger.info(
                "[STUB] Creating stub agent: " + agentId + " (model: " + config.getModel() + ")");
        return new StubAgent(agentId, config);
    }

    /// Returns the provider priority.
    ///
    /// Only stub mode needs to outrank real providers; a {@value #MODEL} name match wins on
    /// the name alone because no real provider claims it.
    ///
    /// @return 1000 in stub mode, 0 otherwise
    @Override
    public int getPriority() {
        return interceptAll ? STUB_MODE_PRIORITY : 0;
    }

    /// Resolves stub mode: an explicit credentials value wins, then the global settings.
    ///
    /// @param credentials map to check for override values, may be null
    /// @return `true` if stub mode is enabled, `false` otherwise
    private static boolean isEnabled(Map<String, String> credentials) {
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

    /// Checks the system property and environment variable.
    ///
    /// @return `true` if enabled globally, `false` otherwise
    private static boolean isEnabledGlobally() {
        String sysProp = System.getProperty(ENABLED_PROPERTY);
        if ("true".equalsIgnoreCase(sysProp)) {
            return true;
        }

        String envVar = System.getenv(ENABLED_KEY);
        return "true".equalsIgnoreCase(envVar);
    }
}
