package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessorPipeline")
class ProcessorPipelineTest {

    @Nested
    @DisplayName("short-circuit behavior")
    class ShortCircuit {

        @Test
        @DisplayName("returns empty when all processors return empty")
        void shouldReturnEmptyWhenAllPass() {
            var pipeline =
                    new ProcessorPipeline(List.of(_ -> Optional.empty(), _ -> Optional.empty()));

            var result = pipeline.execute(minimalContext());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("short-circuits on first terminal result")
        void shouldShortCircuitOnTerminal() {
            var secondProcessorCalled = new boolean[] {false};
            var terminal = new ExecutionResult.Paused(minimalState());

            var pipeline =
                    new ProcessorPipeline(
                            List.of(
                                    _ -> Optional.of(terminal),
                                    _ -> {
                                        secondProcessorCalled[0] = true;
                                        return Optional.empty();
                                    }));

            var result = pipeline.execute(minimalContext());

            assertThat(result).containsSame(terminal);
            assertThat(secondProcessorCalled[0]).isFalse();
        }

        @Test
        @DisplayName("short-circuits on Failure result")
        void shouldShortCircuitOnFailure() {
            var state = minimalState();
            var failure = new ExecutionResult.Failure(state, new IllegalStateException("test"));

            var pipeline =
                    new ProcessorPipeline(
                            List.of(_ -> Optional.of(failure), _ -> Optional.empty()));

            var result = pipeline.execute(minimalContext());

            assertThat(result).containsSame(failure);
        }

        @Test
        @DisplayName("returns empty for empty pipeline")
        void shouldReturnEmptyForEmptyPipeline() {
            var pipeline = new ProcessorPipeline(List.of());

            var result = pipeline.execute(minimalContext());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("processor execution order")
    class ExecutionOrder {

        @Test
        @DisplayName("executes processors in list order")
        void shouldExecuteInOrder() {
            var order = new java.util.ArrayList<String>();

            var pipeline =
                    new ProcessorPipeline(
                            List.of(
                                    _ -> {
                                        order.add("first");
                                        return Optional.empty();
                                    },
                                    _ -> {
                                        order.add("second");
                                        return Optional.empty();
                                    },
                                    _ -> {
                                        order.add("third");
                                        return Optional.empty();
                                    }));

            pipeline.execute(minimalContext());

            assertThat(order).containsExactly("first", "second", "third");
        }
    }

    // --- Test Helpers ---

    private static HensuState minimalState() {
        return new HensuState.Builder()
                .executionId("test-exec")
                .workflowId("test-wf")
                .currentNode("node-a")
                .context(new HashMap<>())
                .history(new ExecutionHistory())
                .build();
    }

    private static ProcessorContext minimalContext() {
        var node =
                StandardNode.builder()
                        .id("node-a")
                        .transitionRules(List.of(new SuccessTransition("node-b")))
                        .build();

        var state = minimalState();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode("node-a")
                        .nodes(Map.of("node-a", node))
                        .build();

        var executionContext = ExecutionContext.builder().state(state).workflow(workflow).build();

        return new ProcessorContext(executionContext, node, NodeResult.success("output", Map.of()));
    }
}
