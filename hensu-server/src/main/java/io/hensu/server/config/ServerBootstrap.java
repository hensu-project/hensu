package io.hensu.server.config;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.server.mcp.McpSidecar;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/// Server bootstrap that registers server-specific components on startup.
///
/// Core components (AgentRegistry, NodeExecutorRegistry, WorkflowExecutor,
/// AgenticNodeExecutor) are fully wired by {@link HensuEnvironmentProducer}
/// via {@link io.hensu.core.HensuFactory}. This bootstrap registers
/// server-specific action handlers on those components.
///
/// ### Registrations
/// - {@link McpSidecar} — action handler for MCP tool calls to external servers
///
/// ### Execution Order
/// Runs during Quarkus startup event, after HensuEnvironment is initialized.
///
/// @see HensuEnvironmentProducer for core component and planning setup
/// @see McpSidecar for MCP tool integration
@ApplicationScoped
public class ServerBootstrap {

    private static final Logger LOG = Logger.getLogger(ServerBootstrap.class);

    private final McpSidecar mcpSidecar;
    private final ActionExecutor actionExecutor;

    @Inject
    public ServerBootstrap(McpSidecar mcpSidecar, ActionExecutor actionExecutor) {
        this.mcpSidecar = mcpSidecar;
        this.actionExecutor = actionExecutor;
    }

    /// Registers server components on application startup.
    ///
    /// @param ignoredEv the startup event
    void onStart(@Observes StartupEvent ignoredEv) {
        LOG.info("Initializing Hensu server components...");

        // Register MCP sidecar as action handler
        // This enables MCP tool calls via: send("mcp", mapOf("tool" to "..."))
        actionExecutor.registerHandler(mcpSidecar);
        LOG.infov("Registered McpSidecar as action handler with ID: {0}", McpSidecar.HANDLER_ID);

        LOG.info("Hensu server initialization complete");
    }
}
