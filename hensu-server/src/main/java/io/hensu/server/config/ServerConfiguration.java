package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.serialization.WorkflowSerializer;
import io.hensu.server.mcp.McpConnection;
import io.hensu.server.mcp.McpConnectionFactory;
import io.hensu.server.mcp.McpException;
import io.hensu.server.planner.LlmPlanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.jboss.logging.Logger;

/// CDI configuration for server-specific beans.
///
/// Core components (AgentRegistry, NodeExecutorRegistry, WorkflowExecutor, ActionExecutor)
/// are produced by {@link HensuEnvironmentProducer} via {@link io.hensu.core.HensuFactory}.
///
/// This class produces:
/// - Server-specific beans (ObjectMapper, WorkflowStateRepository)
/// - Delegating producers that expose HensuEnvironment components for direct injection
/// - Server extensions (PlanExecutor, LlmPlanner, McpConnectionFactory)
@ApplicationScoped
public class ServerConfiguration {

    private static final Logger LOG = Logger.getLogger(ServerConfiguration.class);

    // ========== Utility Beans ==========

    @Produces
    @Singleton
    public ObjectMapper objectMapper() {
        return WorkflowSerializer.createMapper();
    }

    // ========== HensuEnvironment Component Delegates ==========
    // These producers expose HensuEnvironment components for direct CDI injection

    /// Produces the workflow repository from the Hensu environment for CDI injection.
    ///
    /// @param env the initialized environment, not null
    /// @return the workflow repository, never null
    @Produces
    @Singleton
    public WorkflowRepository workflowRepository(HensuEnvironment env) {
        return env.getWorkflowRepository();
    }

    /// Produces the workflow state repository from the Hensu environment for CDI injection.
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

    // ========== Server Extensions ==========

    @Produces
    @Singleton
    public PlanExecutor planExecutor(HensuEnvironment env) {
        return new PlanExecutor(env.getActionExecutor());
    }

    @Produces
    @Singleton
    public LlmPlanner llmPlanner(HensuEnvironment env, ObjectMapper objectMapper) {
        AgentRegistry registry = env.getAgentRegistry();
        // Register a default planning agent if none was explicitly configured.
        // The AgentFactory will route to StubAgentProvider in stub mode or to
        // LangChain4jProvider in production — no hand-rolled fallback needed.
        if (!registry.hasAgent("_planning_agent")) {
            LOG.warn("No _planning_agent registered; registering default planning agent");
            registry.registerAgent(
                    "_planning_agent",
                    AgentConfig.builder()
                            .id("_planning_agent")
                            .role("planner")
                            .model("claude-sonnet-4-5")
                            .temperature(0.3)
                            .build());
        }
        return new LlmPlanner(
                registry.getAgent("_planning_agent")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "_planning_agent was just registered but not found")),
                objectMapper);
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
