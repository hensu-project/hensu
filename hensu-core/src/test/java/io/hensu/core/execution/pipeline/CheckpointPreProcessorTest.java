package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies that a fork recovers as one unit rather than resuming inside a single branch.
///
/// A checkpoint records one cursor. If concurrent branches each wrote one, recovery would
/// resume wherever the last branch happened to be and the join would never see its siblings.
@DisplayName("CheckpointPreProcessor")
class CheckpointPreProcessorTest {

    private final CheckpointPreProcessor processor = new CheckpointPreProcessor();

    @Test
    @DisplayName("checkpoints the main execution state")
    void checkpointsParentState() {
        List<HensuState> checkpointed = new ArrayList<>();
        var ctx = context(parentState(), checkpointed::add);

        processor.process(ctx);

        assertThat(checkpointed).hasSize(1);
    }

    @Test
    @DisplayName("suppresses checkpoints for fork branch states")
    void suppressesBranchState() {
        List<HensuState> checkpointed = new ArrayList<>();
        var ctx = context(parentState().branch("worker"), checkpointed::add);

        var outcome = processor.process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(checkpointed).isEmpty();
    }

    private HensuState parentState() {
        return new HensuState.Builder()
                .executionId("exec-1")
                .workflowId("wf-1")
                .currentNode("node")
                .context(new HashMap<>())
                .history(new ExecutionHistory())
                .build();
    }

    private ProcessorContext context(
            HensuState state, java.util.function.Consumer<HensuState> sink) {
        var node =
                StandardNode.builder()
                        .id("node")
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();
        var workflow =
                Workflow.builder().id("wf-1").startNode("node").nodes(Map.of("node", node)).build();
        var listener =
                new ExecutionListener() {
                    @Override
                    public void onCheckpoint(HensuState checkpointed) {
                        sink.accept(checkpointed);
                    }
                };
        var execCtx =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(workflow)
                        .listener(listener)
                        .build();
        return new ProcessorContext(execCtx, node, null);
    }
}
