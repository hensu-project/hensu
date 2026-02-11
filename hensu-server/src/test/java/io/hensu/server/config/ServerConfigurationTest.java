package io.hensu.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.plan.PlanExecutor;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.server.mcp.McpConnectionFactory;
import io.hensu.server.mcp.McpException;
import io.hensu.server.planner.LlmPlanner;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ServerConfigurationTest {

    private ServerConfiguration config;
    private HensuEnvironment env;

    @BeforeEach
    void setUp() {
        config = new ServerConfiguration();
        env = mock(HensuEnvironment.class);
    }

    @Nested
    class UtilityBeans {

        @Test
        void shouldProduceObjectMapper() {
            ObjectMapper mapper = config.objectMapper();
            assertThat(mapper).isNotNull();
        }

        @Test
        void shouldProduceWorkflowRepository() {
            WorkflowRepository mockRepo = mock(WorkflowRepository.class);
            when(env.getWorkflowRepository()).thenReturn(mockRepo);
            assertThat(config.workflowRepository(env)).isSameAs(mockRepo);
        }

        @Test
        void shouldProduceWorkflowStateRepository() {
            WorkflowStateRepository mockRepo = mock(WorkflowStateRepository.class);
            when(env.getWorkflowStateRepository()).thenReturn(mockRepo);
            assertThat(config.workflowStateRepository(env)).isSameAs(mockRepo);
        }
    }

    @Nested
    class EnvironmentDelegation {

        @Test
        void shouldDelegateWorkflowExecutor() {
            WorkflowExecutor mockExecutor = mock(WorkflowExecutor.class);
            when(env.getWorkflowExecutor()).thenReturn(mockExecutor);

            assertThat(config.workflowExecutor(env)).isSameAs(mockExecutor);
        }

        @Test
        void shouldDelegateAgentRegistry() {
            AgentRegistry mockRegistry = mock(AgentRegistry.class);
            when(env.getAgentRegistry()).thenReturn(mockRegistry);

            assertThat(config.agentRegistry(env)).isSameAs(mockRegistry);
        }

        @Test
        void shouldDelegateNodeExecutorRegistry() {
            NodeExecutorRegistry mockRegistry = mock(NodeExecutorRegistry.class);
            when(env.getNodeExecutorRegistry()).thenReturn(mockRegistry);

            assertThat(config.nodeExecutorRegistry(env)).isSameAs(mockRegistry);
        }
    }

    @Nested
    class ServerExtensions {

        @Test
        void shouldProducePlanExecutor() {
            ActionExecutor mockAction = mock(ActionExecutor.class);
            when(env.getActionExecutor()).thenReturn(mockAction);

            PlanExecutor planExecutor = config.planExecutor(env);
            assertThat(planExecutor).isNotNull();
        }

        @Test
        void shouldProduceLlmPlannerRegisteringDefaultAgent() {
            Agent mockAgent = mock(Agent.class);
            AgentRegistry mockRegistry = mock(AgentRegistry.class);
            when(env.getAgentRegistry()).thenReturn(mockRegistry);
            when(mockRegistry.hasAgent("_planning_agent")).thenReturn(false);
            when(mockRegistry.registerAgent(eq("_planning_agent"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            when(mockRegistry.getAgent("_planning_agent")).thenReturn(Optional.of(mockAgent));

            ObjectMapper mapper = config.objectMapper();
            LlmPlanner planner = config.llmPlanner(env, mapper);

            assertThat(planner).isNotNull();
            verify(mockRegistry).registerAgent(eq("_planning_agent"), any(AgentConfig.class));
        }

        @Test
        void shouldProduceLlmPlannerWithExistingAgent() {
            Agent mockAgent = mock(Agent.class);
            AgentRegistry mockRegistry = mock(AgentRegistry.class);
            when(env.getAgentRegistry()).thenReturn(mockRegistry);
            when(mockRegistry.hasAgent("_planning_agent")).thenReturn(true);
            when(mockRegistry.getAgent("_planning_agent")).thenReturn(Optional.of(mockAgent));

            ObjectMapper mapper = config.objectMapper();
            LlmPlanner planner = config.llmPlanner(env, mapper);

            assertThat(planner).isNotNull();
        }

        @Test
        void shouldProduceStubMcpConnectionFactory() {
            McpConnectionFactory factory = config.mcpConnectionFactory();

            assertThat(factory.supports("http://any:3000")).isFalse();
            assertThatThrownBy(
                            () ->
                                    factory.create(
                                            "http://any:3000",
                                            Duration.ofSeconds(30),
                                            Duration.ofSeconds(60)))
                    .isInstanceOf(McpException.class)
                    .hasMessageContaining("not configured");
        }
    }

    @Nested
    class PlanningAgentRegistration {

        @Test
        void shouldRegisterDefaultAgentWithExpectedConfig() {
            Agent mockAgent = mock(Agent.class);
            AgentRegistry mockRegistry = mock(AgentRegistry.class);
            when(env.getAgentRegistry()).thenReturn(mockRegistry);
            when(mockRegistry.hasAgent("_planning_agent")).thenReturn(false);
            when(mockRegistry.registerAgent(eq("_planning_agent"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            when(mockRegistry.getAgent("_planning_agent")).thenReturn(Optional.of(mockAgent));

            config.llmPlanner(env, config.objectMapper());

            verify(mockRegistry).registerAgent(eq("_planning_agent"), any(AgentConfig.class));
        }
    }
}
