package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HistoryProcessor")
class HistoryPostProcessorTest {

    private HistoryPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new HistoryPostProcessor();
    }

    @Test
    @DisplayName("appends execution step to history")
    void shouldAppendStep() {
        var ctx = minimalContext("step-node");

        processor.process(ctx);

        var steps = ctx.state().getHistory().getSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().getNodeId()).isEqualTo("step-node");
    }

    @Test
    @DisplayName("step contains state snapshot")
    void shouldContainSnapshot() {
        var ctx = minimalContext("snap-node");
        ctx.state().getContext().put("key", "value");

        processor.process(ctx);

        var step = ctx.state().getHistory().getSteps().getFirst();
        assertThat(step.getSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("step contains node result")
    void shouldContainResult() {
        var ctx = minimalContext("result-node");

        processor.process(ctx);

        var step = ctx.state().getHistory().getSteps().getFirst();
        assertThat(step.getResult()).isNotNull();
        assertThat(step.getResult().getOutput()).isEqualTo("test output");
    }

    @Test
    @DisplayName("accumulates steps across multiple calls")
    void shouldAccumulateSteps() {
        var ctx = minimalContext("node-1");

        processor.process(ctx);
        processor.process(ctx);
        processor.process(ctx);

        assertThat(ctx.state().getHistory().getSteps()).hasSize(3);
    }

    @Test
    @DisplayName("always returns empty")
    void shouldAlwaysReturnEmpty() {
        var ctx = minimalContext("node");

        var result = processor.process(ctx);

        assertThat(result).isEmpty();
    }

    // --- Helpers ---

    private ProcessorContext minimalContext(String nodeId) {
        var node =
                StandardNode.builder()
                        .id(nodeId)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(nodeId)
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode(nodeId)
                        .nodes(Map.of(nodeId, node))
                        .build();

        var execCtx = ExecutionContext.builder().state(state).workflow(workflow).build();

        return new ProcessorContext(execCtx, node, NodeResult.success("test output", Map.of()));
    }
}
