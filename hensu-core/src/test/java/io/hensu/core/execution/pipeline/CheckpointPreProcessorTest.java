package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.ExecutionJournal;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies where each flow's checkpoint goes.
///
/// The execution row holds one cursor, so only the main flow may write it. A nested flow's
/// checkpoint must go to the journal under the flow's own scope: written to the row, the
/// last branch to checkpoint would move the execution's cursor into its own sub-flow, and
/// recovery would resume there with its siblings never having run.
@DisplayName("CheckpointPreProcessor")
class CheckpointPreProcessorTest {

    private final CheckpointPreProcessor processor = new CheckpointPreProcessor();

    @Test
    @DisplayName("checkpoints the main execution state")
    void checkpointsParentState() {
        List<HensuState> checkpointed = new ArrayList<>();
        Map<String, HensuSnapshot> journaled = new HashMap<>();
        var ctx = context(parentState(), checkpointed::add, journaled);

        processor.process(ctx);

        assertThat(checkpointed).hasSize(1);
        assertThat(journaled).isEmpty();
    }

    @Test
    @DisplayName(
            "records a nested flow's checkpoint in the journal under its scope, not on the row")
    void nestedFlowCheckpointsToJournal() {
        List<HensuState> checkpointed = new ArrayList<>();
        Map<String, HensuSnapshot> journaled = new HashMap<>();
        var ctx =
                context(
                        parentState().branch("worker", "3/f:worker/"),
                        checkpointed::add,
                        journaled);

        var outcome = processor.process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(checkpointed).isEmpty();
        assertThat(journaled).containsOnlyKeys("3/f:worker/");
        assertThat(journaled.get("3/f:worker/").currentNodeId()).isEqualTo("worker");
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
            HensuState state,
            java.util.function.Consumer<HensuState> sink,
            Map<String, HensuSnapshot> journaled) {
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
                        .journal(
                                new ExecutionJournal() {
                                    @Override
                                    public String turn(
                                            String position,
                                            int index,
                                            java.util.function.Supplier<String> live) {
                                        return live.get();
                                    }

                                    @Override
                                    public Optional<HensuSnapshot> flowCheckpoint(String scope) {
                                        return Optional.ofNullable(journaled.get(scope));
                                    }

                                    @Override
                                    public void recordFlowCheckpoint(
                                            String scope, HensuSnapshot snapshot) {
                                        journaled.put(scope, snapshot);
                                    }
                                })
                        .build();
        return new ProcessorContext(execCtx, node, null);
    }
}
