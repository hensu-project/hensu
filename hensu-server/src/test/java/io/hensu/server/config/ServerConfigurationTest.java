package io.hensu.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.server.mcp.McpConnectionFactory;
import io.hensu.server.mcp.McpException;
import java.time.Duration;
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
}
