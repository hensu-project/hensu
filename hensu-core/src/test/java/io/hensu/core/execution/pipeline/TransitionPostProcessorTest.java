package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TransitionProcessor")
class TransitionPostProcessorTest {

    private TransitionPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransitionPostProcessor();
    }

    // — Error paths ———————————————————————————————————————————————————————

    @Test
    @DisplayName("throws when no transition matches — catches missing-transition misconfiguration")
    void shouldThrowWhenNoTransitionMatches() {
        // Integration tests always configure transitions; this exercises the error branch
        // that only fires on misconfiguration. Message must contain the node ID for diagnosis.
        var ctx = contextWithTransitions("orphan", List.of());

        assertThatThrownBy(() -> processor.process(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan");
    }

    @Test
    @DisplayName("LoopNode without loop_exit_target throws — LoopNode has no fallback transitions")
    void shouldThrowWhenLoopNodeHasNoExitTarget() {
        // LoopNode.getTransitionRules() always returns List.of() — the only legal
        // exit path is via loop_exit_target in context. If that key is absent, the
        // processor reaches the no-match throw, exposing a workflow misconfiguration.
        var ctx = loopNodeContext();

        assertThatThrownBy(() -> processor.process(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loop");
    }

    // — LoopNode loop_exit_target ————————————————————————————————————————

    @Test
    @DisplayName("LoopNode with loop_exit_target in context redirects to that target")
    void shouldUseLoopExitTargetWhenSetInContext() {
        var ctx = loopNodeContext();
        ctx.state().getContext().put("loop_exit_target", "exit-node");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("exit-node");
    }

    @Test
    @DisplayName("loopBreakTarget takes priority over loop_exit_target for LoopNode")
    void shouldPreferLoopBreakTargetOverLoopExitTarget() {
        // Two override mechanisms exist for LoopNode. loopBreakTarget is consumed
        // before the LoopNode branch even runs, so it must always win.
        var ctx = loopNodeContext();
        ctx.state().setLoopBreakTarget("break-target");
        ctx.state().getContext().put("loop_exit_target", "exit-node");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("break-target");
    }

    @Test
    @DisplayName("loopBreakTarget is cleared after being consumed on a LoopNode")
    void shouldClearLoopBreakTargetAfterConsumingOnLoopNode() {
        var ctx = loopNodeContext();
        ctx.state().setLoopBreakTarget("break-target");

        processor.process(ctx);

        assertThat(ctx.state().getLoopBreakTarget()).isNull();
    }

    // — Loop break override (StandardNode) ———————————————————————————————

    @Test
    @DisplayName("loopBreakTarget overrides normal transition on StandardNode")
    void shouldPrioritizeLoopBreakOverRuleOnStandardNode() {
        var ctx = contextWithTransitions("node", List.of(new SuccessTransition("rule-target")));
        ctx.state().setLoopBreakTarget("override-target");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("override-target");
    }

    @Test
    @DisplayName("loopBreakTarget is cleared after consuming it on StandardNode")
    void shouldClearLoopBreakTargetAfterConsuming() {
        var ctx = contextWithTransitions("node", List.of(new SuccessTransition("next")));
        ctx.state().setLoopBreakTarget("break-target");

        processor.process(ctx);

        assertThat(ctx.state().getLoopBreakTarget()).isNull();
    }

    // — Backtrack skip ————————————————————————————————————————————————————

    @Test
    @DisplayName("skips transition when currentNode already changed by a prior processor")
    void shouldSkipWhenPriorProcessorAlreadyRedirected() {
        // ReviewPostProcessor or RubricPostProcessor may mutate currentNode before
        // this processor runs. TransitionPostProcessor must not overwrite that redirect.
        var ctx = contextWithTransitions("original", List.of(new SuccessTransition("normal-next")));
        ctx.state().setCurrentNode("already-redirected");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("already-redirected");
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private ProcessorContext contextWithTransitions(String nodeId, List<TransitionRule> rules) {
        Node node = StandardNode.builder().id(nodeId).transitionRules(rules).build();
        return buildContext(nodeId, node);
    }

    private ProcessorContext loopNodeContext() {
        // LoopNode has no transition rules — loop_exit_target in context is the only exit path.
        LoopNode loopNode = new LoopNode("loop");
        return buildContext("loop", loopNode);
    }

    private ProcessorContext buildContext(String nodeId, Node node) {
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
