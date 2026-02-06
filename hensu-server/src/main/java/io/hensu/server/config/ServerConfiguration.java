package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.serialization.WorkflowSerializer;
import io.hensu.server.mcp.McpConnection;
import io.hensu.server.mcp.McpConnectionFactory;
import io.hensu.server.mcp.McpException;
import io.hensu.server.persistence.InMemoryWorkflowRepository;
import io.hensu.server.persistence.InMemoryWorkflowStateRepository;
import io.hensu.server.persistence.WorkflowRepository;
import io.hensu.server.persistence.WorkflowStateRepository;
import io.hensu.server.planner.LlmPlanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
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

    @Produces
    @Singleton
    public WorkflowStateRepository workflowStateRepository() {
        return new InMemoryWorkflowStateRepository();
    }

    @Produces
    @Singleton
    public WorkflowRepository workflowRepository() {
        return new InMemoryWorkflowRepository();
    }

    // ========== HensuEnvironment Component Delegates ==========
    // These producers expose HensuEnvironment components for direct CDI injection

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
        // Get planning agent from registry, or create stub if not configured
        Agent planningAgent =
                env.getAgentRegistry()
                        .getAgent("_planning_agent")
                        .orElseGet(this::createStubPlanningAgent);
        return new LlmPlanner(planningAgent, objectMapper);
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

    private Agent createStubPlanningAgent() {
        LOG.warn("Planning agent not configured, using stub");
        return new Agent() {
            @Override
            public AgentResponse execute(String prompt, Map<String, Object> context) {
                return AgentResponse.Error.of("Planning agent not configured");
            }

            @Override
            public String getId() {
                return "_planning_agent";
            }

            @Override
            public AgentConfig getConfig() {
                return null;
            }
        };
    }
}
