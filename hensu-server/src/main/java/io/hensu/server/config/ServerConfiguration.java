package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.serialization.WorkflowSerializer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/// CDI configuration for server-specific beans.
///
/// Core components (AgentRegistry, NodeExecutorRegistry, WorkflowExecutor, ActionExecutor)
/// are produced by {@link HensuEnvironmentProducer} via {@link io.hensu.core.HensuFactory}.
///
/// This class produces:
/// - Server-specific beans (ObjectMapper)
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
}
