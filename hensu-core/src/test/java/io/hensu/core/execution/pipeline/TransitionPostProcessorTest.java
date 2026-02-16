package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TransitionProcessor")
class TransitionPostProcessorTest {

    private TransitionPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransitionPostProcessor();
    }

    @Nested
    @DisplayName("rule-based transitions")
    class RuleBasedTransitions {

        @Test
        @DisplayName("sets current node to target from SuccessTransition")
        void shouldRedirectOnSuccess() {
            var ctx = contextWithTransitions("node-a", List.of(new SuccessTransition("node-b")));

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            assertThat(ctx.state().getCurrentNode()).isEqualTo("node-b");
        }

        @Test
        @DisplayName("throws when no transition matches")
        void shouldThrowWhenNoTransition() {
            var ctx = contextWithTransitions("orphan", List.of());

            assertThatThrownBy(() -> processor.process(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No valid transition from orphan");
        }

        @Test
        @DisplayName("evaluates transitions in order, first match wins")
        void shouldUseFirstMatch() {
            var ctx =
                    contextWithTransitions(
                            "node",
                            List.of(
                                    new SuccessTransition("first"),
                                    new SuccessTransition("second")));

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("first");
        }
    }

    @Nested
    @DisplayName("loop break override")
    class LoopBreakOverride {

        @Test
        @DisplayName("uses loop break target when set")
        void shouldUseLoopBreakTarget() {
            var ctx = contextWithTransitions("node", List.of(new SuccessTransition("normal-next")));
            ctx.state().setLoopBreakTarget("break-target");

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("break-target");
        }

        @Test
        @DisplayName("clears loop break target after consuming it")
        void shouldClearLoopBreakTarget() {
            var ctx = contextWithTransitions("node", List.of(new SuccessTransition("next")));
            ctx.state().setLoopBreakTarget("break-target");

            processor.process(ctx);

            assertThat(ctx.state().getLoopBreakTarget()).isNull();
        }

        @Test
        @DisplayName("loop break takes precedence over transition rules")
        void shouldPrioritizeLoopBreak() {
            var ctx = contextWithTransitions("node", List.of(new SuccessTransition("rule-target")));
            ctx.state().setLoopBreakTarget("override-target");

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("override-target");
        }
    }

    // --- Helpers ---

    private ProcessorContext contextWithTransitions(String nodeId, List<TransitionRule> rules) {

        Node node = StandardNode.builder().id(nodeId).transitionRules(rules).build();

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

        return new ProcessorContext(execCtx, node, NodeResult.success("output", Map.of()));
    }
}
