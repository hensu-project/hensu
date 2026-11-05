package io.hensu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentProvider;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("HensuFactory")
@ExtendWith(MockitoExtension.class)
class HensuFactoryTest {

    private HensuEnvironment environment;

    @Mock private NodeExecutorRegistry mockNodeExecutorRegistry;
    @Mock private AgentRegistry mockAgentRegistry;

    @Nested
    @DisplayName("builder")
    class Builder {

        @Test
        @DisplayName("executes a trivial workflow to completion")
        void shouldExecuteTrivialWorkflowToCompletion() throws Exception {
            environment = HensuFactory.builder().build();

            var workflow =
                    Workflow.builder()
                            .id("smoke-test")
                            .startNode("done")
                            .nodes(
                                    Map.of(
                                            "done",
                                            EndNode.builder()
                                                    .id("done")
                                                    .status(ExitStatus.SUCCESS)
                                                    .build()))
                            .build();

            var result = environment.getWorkflowExecutor().execute(workflow, Map.of());

            assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) result).exitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }

        @Test
        @DisplayName("throws NullPointerException on null workflow")
        void shouldRejectNullWorkflow() {
            environment = HensuFactory.builder().build();

            assertThatThrownBy(() -> environment.getWorkflowExecutor().execute(null, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("wires custom NodeExecutorRegistry")
        void shouldWireCustomNodeExecutorRegistry() {
            environment =
                    HensuFactory.builder().nodeExecutorRegistry(mockNodeExecutorRegistry).build();

            assertThat(environment.getNodeExecutorRegistry()).isSameAs(mockNodeExecutorRegistry);
        }

        @Test
        @DisplayName("wires custom AgentRegistry")
        void shouldWireCustomAgentRegistry() {
            environment = HensuFactory.builder().agentRegistry(mockAgentRegistry).build();

            assertThat(environment.getAgentRegistry()).isSameAs(mockAgentRegistry);
        }
    }

    @Nested
    @DisplayName("createEnvironment overloads")
    class CreateEnvironment {

        @Test
        @DisplayName("wires custom NodeExecutorRegistry")
        void shouldWireCustomNodeExecutorRegistry() {
            environment =
                    HensuFactory.createEnvironment(
                            new HensuConfig(), mockNodeExecutorRegistry, mockAgentRegistry);

            assertThat(environment.getNodeExecutorRegistry()).isSameAs(mockNodeExecutorRegistry);
        }

        @Test
        @DisplayName("wires custom AgentRegistry")
        void shouldWireCustomAgentRegistry() {
            environment =
                    HensuFactory.createEnvironment(
                            new HensuConfig(), mockNodeExecutorRegistry, mockAgentRegistry);

            assertThat(environment.getAgentRegistry()).isSameAs(mockAgentRegistry);
        }
    }

    @Nested
    @DisplayName("stub mode")
    class StubMode {

        @Mock private AgentProvider realProvider;
        @Mock private Agent realAgent;

        @BeforeEach
        void stubRealProvider() {
            lenient().when(realProvider.getName()).thenReturn("real");
            lenient().when(realProvider.supportsModel("real-model")).thenReturn(true);
            lenient()
                    .when(realProvider.createAgent(eq("reviewer"), any(), any()))
                    .thenReturn(realAgent);
        }

        @Test
        @DisplayName("stays scoped to the environment that enabled it")
        void shouldNotLeakStubModeIntoALaterEnvironment() {
            HensuFactory.builder().stubMode(true).build();

            environment =
                    HensuFactory.builder()
                            .stubMode(false)
                            .agentProviders(List.of(realProvider))
                            .build();

            assertThat(environment.getAgentRegistry().registerAgent("reviewer", realModel()))
                    .isSameAs(realAgent);
            assertThat(System.getProperty("hensu.stub.enabled")).isNull();
        }

        @Test
        @DisplayName("is enabled by HENSU_STUB_ENABLED in the credentials map")
        void shouldEnableStubModeFromCredentialsKey() {
            environment =
                    HensuFactory.builder()
                            .credential("HENSU_STUB_ENABLED", "true")
                            .agentProviders(List.of(realProvider))
                            .build();

            assertThat(environment.getAgentRegistry().registerAgent("reviewer", realModel()))
                    .isInstanceOf(StubAgent.class);
        }

        @Test
        @DisplayName("is disabled by an explicit false despite the global switch")
        void shouldLetAnExplicitFalseOverrideTheSystemProperty() {
            System.setProperty("hensu.stub.enabled", "true");
            try {
                environment =
                        HensuFactory.builder()
                                .stubMode(false)
                                .agentProviders(List.of(realProvider))
                                .build();

                assertThat(environment.getAgentRegistry().registerAgent("reviewer", realModel()))
                        .isSameAs(realAgent);
            } finally {
                System.clearProperty("hensu.stub.enabled");
            }
        }

        private AgentConfig realModel() {
            return AgentConfig.builder()
                    .id("reviewer")
                    .role("reviewer")
                    .model("real-model")
                    .build();
        }
    }
}
