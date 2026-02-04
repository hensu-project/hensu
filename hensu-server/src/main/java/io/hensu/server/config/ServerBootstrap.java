package io.hensu.server.config;

import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.server.executor.AgenticNodeExecutor;
import io.hensu.server.mcp.McpSidecar;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/// Server bootstrap that registers server-specific components on startup.
///
/// Performs the following registrations:
/// - Registers {@link AgenticNodeExecutor} to override default StandardNode handling
/// - Registers {@link McpSidecar} as an action handler for MCP tool calls
/// - Registers {@link ExecutionEventBroadcaster} for SSE event streaming
///
/// ### Execution Order
/// Runs during Quarkus startup event, after CDI beans are initialized.
///
/// @see AgenticNodeExecutor for planning-aware node execution
/// @see McpSidecar for MCP tool integration
/// @see ExecutionEventBroadcaster for SSE event streaming
@ApplicationScoped
public class ServerBootstrap {

    private static final Logger LOG = Logger.getLogger(ServerBootstrap.class);

    private final AgenticNodeExecutor agenticExecutor;
    private final McpSidecar mcpSidecar;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final ActionExecutor actionExecutor;
    private final PlanExecutor planExecutor;
    private final ExecutionEventBroadcaster eventBroadcaster;

    @Inject
    public ServerBootstrap(
            AgenticNodeExecutor agenticExecutor,
            McpSidecar mcpSidecar,
            NodeExecutorRegistry nodeExecutorRegistry,
            ActionExecutor actionExecutor,
            PlanExecutor planExecutor,
            ExecutionEventBroadcaster eventBroadcaster) {
        this.agenticExecutor = agenticExecutor;
        this.mcpSidecar = mcpSidecar;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.actionExecutor = actionExecutor;
        this.planExecutor = planExecutor;
        this.eventBroadcaster = eventBroadcaster;
    }

    /// Registers server components on application startup.
    ///
    /// @param ev the startup event
    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing Hensu server components...");

        // Register agentic executor to override default StandardNode handling
        // This enables planning support for standard workflow nodes
        nodeExecutorRegistry.register(agenticExecutor);
        LOG.info("Registered AgenticNodeExecutor for StandardNode");

        // Register MCP sidecar as action handler
        // This enables MCP tool calls via: send("mcp", mapOf("tool" to "..."))
        actionExecutor.registerHandler(mcpSidecar);
        LOG.infov("Registered McpSidecar as action handler with ID: {0}", McpSidecar.HANDLER_ID);

        // Register event broadcaster as PlanObserver for SSE streaming
        // This enables real-time event streaming via: GET /api/v1/executions/{id}/events
        planExecutor.addObserver(eventBroadcaster);
        LOG.info("Registered ExecutionEventBroadcaster for SSE streaming");

        LOG.info("Hensu server initialization complete");
    }
}
