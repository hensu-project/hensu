package io.hensu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
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
    @Mock private ExecutorService mockExecutorService;

    @AfterEach
    void tearDown() {
        if (environment != null) {
            environment.close();
        }
    }

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
                            new HensuConfig(),
                            mockNodeExecutorRegistry,
                            mockAgentRegistry,
                            mockExecutorService);

            assertThat(environment.getNodeExecutorRegistry()).isSameAs(mockNodeExecutorRegistry);
        }

        @Test
        @DisplayName("wires custom AgentRegistry")
        void shouldWireCustomAgentRegistry() {
            environment =
                    HensuFactory.createEnvironment(
                            new HensuConfig(),
                            mockNodeExecutorRegistry,
                            mockAgentRegistry,
                            mockExecutorService);

            assertThat(environment.getAgentRegistry()).isSameAs(mockAgentRegistry);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close does not throw")
        void shouldCloseWithoutException() {
            environment = HensuFactory.builder().build();

            assertThatCode(() -> environment.close()).doesNotThrowAnyException();
        }
    }
}
