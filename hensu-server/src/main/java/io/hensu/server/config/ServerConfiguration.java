package io.hensu.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentNotFoundException;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.storage.rubric.InMemoryRubricRepository;
import io.hensu.core.storage.workflow.InMemoryWorkflowStateRepository;
import io.hensu.core.storage.workflow.WorkflowStateRepository;
import io.hensu.server.mcp.McpConnection;
import io.hensu.server.mcp.McpConnectionFactory;
import io.hensu.server.mcp.McpException;
import io.hensu.server.planner.LlmPlanner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.jboss.logging.Logger;

/// CDI configuration for server beans.
///
/// Provides producer methods for core module classes that are not CDI beans,
/// and for server components that require complex initialization.
@ApplicationScoped
public class ServerConfiguration {

    private static final Logger LOG = Logger.getLogger(ServerConfiguration.class);

    @Produces
    @Singleton
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Produces
    @Singleton
    public AgentRegistry agentRegistry() {
        // Stub registry until LangChain4j integration is configured
        return new AgentRegistry() {
            private final Map<String, Agent> agents = new ConcurrentHashMap<>();

            @Override
            public Optional<Agent> getAgent(String id) {
                return Optional.ofNullable(agents.get(id));
            }

            @Override
            public Agent getAgentOrThrow(String id) throws AgentNotFoundException {
                return getAgent(id)
                        .orElseThrow(() -> new AgentNotFoundException("Agent not found: " + id));
            }

            @Override
            public Agent registerAgent(String agentId, AgentConfig config) {
                LOG.warnv("Agent registration not configured: {0}", agentId);
                Agent stubAgent = new StubAgent(agentId, config);
                agents.put(agentId, stubAgent);
                return stubAgent;
            }

            @Override
            public void registerAgents(Map<String, AgentConfig> configs) {
                configs.forEach(this::registerAgent);
            }

            @Override
            public boolean hasAgent(String agentId) {
                return agents.containsKey(agentId);
            }
        };
    }

    @Produces
    @Singleton
    public NodeExecutorRegistry nodeExecutorRegistry() {
        return new DefaultNodeExecutorRegistry();
    }

    @Produces
    @Singleton
    public RubricEngine rubricEngine() {
        return new RubricEngine(new InMemoryRubricRepository(), new DefaultRubricEvaluator());
    }

    @Produces
    @Singleton
    public WorkflowExecutor workflowExecutor(
            NodeExecutorRegistry nodeExecutorRegistry,
            AgentRegistry agentRegistry,
            ExecutorService executorService,
            RubricEngine rubricEngine) {
        return new WorkflowExecutor(
                nodeExecutorRegistry, agentRegistry, executorService, rubricEngine, null);
    }

    @Produces
    @Singleton
    public ActionExecutor actionExecutor() {
        // Stub action executor until MCP integration is configured
        return (action, _) -> {
            LOG.warnv("Action execution not configured: {0}", action);
            return ActionExecutor.ActionResult.failure("Action executor not configured");
        };
    }

    @Produces
    @Singleton
    public PlanExecutor planExecutor(ActionExecutor actionExecutor) {
        return new PlanExecutor(actionExecutor);
    }

    @Produces
    @Singleton
    public LlmPlanner llmPlanner(ObjectMapper objectMapper) {
        // Stub planning agent until configured
        Agent stubPlanningAgent = new StubAgent("_planning_agent", null);
        return new LlmPlanner(stubPlanningAgent, objectMapper);
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

    @Produces
    @Singleton
    public WorkflowStateRepository workflowStateRepository() {
        return new InMemoryWorkflowStateRepository();
    }

    /// Stub agent implementation for unconfigured agents.
    private static class StubAgent implements Agent {
        private final String id;
        private final AgentConfig config;

        StubAgent(String id, AgentConfig config) {
            this.id = id;
            this.config = config;
        }

        @Override
        public AgentResponse execute(String prompt, Map<String, Object> context) {
            return AgentResponse.Error.of("Agent not configured: " + id);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AgentConfig getConfig() {
            return config;
        }
    }
}
