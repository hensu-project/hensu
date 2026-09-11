package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.mcp.JsonRpc;
import io.hensu.mcp.McpConnection;
import io.hensu.mcp.McpConnectionFactory;
import io.hensu.mcp.McpException;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;

/// CDI configuration for server-specific beans.
///
/// Core components (AgentRegistry, NodeExecutorRegistry, WorkflowExecutor, ActionExecutor)
/// are produced by {@link HensuEnvironmentProducer} via {@link io.hensu.core.HensuFactory}.
///
/// This class produces:
/// - Server-specific beans (ObjectMapper)
/// - Plain objects from the shared `hensu-mcp` module that the server injects ({@link JsonRpc})
/// - Delegating producers that expose {@link HensuEnvironment} components for direct injection
@ApplicationScoped
public class ServerConfiguration {

    // ========== Utility Beans ==========

    @Produces
    @Singleton
    public ObjectMapper objectMapper() {
        return WorkflowSerializer.createMapper();
    }

    // ========== HensuEnvironment Component Delegates ==========

    /// Exposes the workflow repository for direct CDI injection.
    ///
    /// @param env the initialized environment, not null
    /// @return the workflow repository, never null
    @Produces
    @Singleton
    public WorkflowRepository workflowRepository(HensuEnvironment env) {
        return env.getWorkflowRepository();
    }

    /// Exposes the workflow state repository for direct CDI injection.
    ///
    /// @param env the initialized environment, not null
    /// @return the workflow state repository, never null
    @Produces
    @Singleton
    public WorkflowStateRepository workflowStateRepository(HensuEnvironment env) {
        return env.getWorkflowStateRepository();
    }

    @Produces
    @Singleton
    public WorkflowExecutor workflowExecutor(HensuEnvironment env) {
        return env.getWorkflowExecutor();
    }

    @Produces
    @Singleton
    public AgentRegistry agentRegistry(HensuEnvironment env) {
        return env.getAgentRegistry();
    }

    @Produces
    @Singleton
    public NodeExecutorRegistry nodeExecutorRegistry(HensuEnvironment env) {
        return env.getNodeExecutorRegistry();
    }

    // ========== MCP Infrastructure ==========

    /// Exposes the JSON-RPC helper from the shared MCP module as an injectable bean.
    ///
    /// {@link JsonRpc} is a plain object rather than a CDI bean so the CLI can
    /// construct one directly; the server needs an injectable instance, and a
    /// producer that instantiates the concrete type keeps the native image free
    /// of dynamic class loading.
    ///
    /// @param mapper the server's configured object mapper, not null
    /// @return the JSON-RPC helper, never null
    @Produces
    @Singleton
    public JsonRpc jsonRpc(ObjectMapper mapper) {
        return new JsonRpc(mapper);
    }

    @Produces
    @Singleton
    public McpConnectionFactory mcpConnectionFactory() {
        // Stub implementation until MCP is fully configured
        return new McpConnectionFactory() {
            @Override
            public McpConnection create(
                    String endpoint, Duration connectionTimeout, Duration readTimeout)
                    throws McpException {
                throw new McpException("MCP connection factory not configured");
            }

            @Override
            public boolean supports(String endpoint) {
                return false;
            }
        };
    }
}
