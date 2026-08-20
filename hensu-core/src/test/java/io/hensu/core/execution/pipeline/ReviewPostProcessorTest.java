package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies that a review decision reaches the graph as a
/// {@link io.hensu.core.review.ReviewVerdict} and never as
/// an engine variable.
///
/// The engine-variable assertions are the load-bearing ones. An earlier design had this processor
/// overwrite `approved`, which made "the human decides" depend on the pipeline's processor order
/// and gave one untyped boolean three writers with three meanings.
@DisplayName("ReviewPostProcessor")
class ReviewPostProcessorTest {

    private static final String NODE = "review-node";

    @Test
    @DisplayName("approve records an approving verdict and leaves the agent's 'approved' alone")
    void approveRecordsVerdict() {
        var ctx = reviewContext(bothArms(), agentSaid(false));

        var outcome = processorDeciding(new ReviewDecision.Approve(null)).process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(ctx.state().getReviewVerdict()).isNotNull();
        assertThat(ctx.state().getReviewVerdict().approved()).isTrue();
        assertThat(ctx.state().getContext()).containsEntry(EngineVariables.APPROVED, false);
    }

    @Test
    @DisplayName("approve merges the reviewer's context edits")
    void approveMergesContextEdits() {
        var ctx = reviewContext(bothArms(), agentSaid(true));

        processorDeciding(new ReviewDecision.Approve(Map.of("limit", 75000))).process(ctx);

        assertThat(ctx.state().getContext()).containsEntry("limit", 75000);
    }

    @Test
    @DisplayName("reject on a node with an onRejection arm continues so the graph can route it")
    void rejectWithArmRoutesThroughGraph() {
        var ctx = reviewContext(bothArms(), agentSaid(true));

        var outcome = processorDeciding(new ReviewDecision.Reject("limit too high")).process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(ctx.state().getReviewVerdict().approved()).isFalse();
        assertThat(ctx.state().getReviewVerdict().reason()).isEqualTo("limit too high");
        assertThat(ctx.state().getContext()).containsEntry(EngineVariables.APPROVED, true);
    }

    @Test
    @DisplayName("reject on a node with no rejection arm terminates — there is nowhere to route")
    void rejectWithoutArmTerminates() {
        var ctx = reviewContext(List.of(new SuccessTransition("next")), agentSaid(true));

        var outcome = processorDeciding(new ReviewDecision.Reject("no")).process(ctx);

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        ProcessorOutcome.Terminal.class,
                        terminal ->
                                assertThat(terminal.result())
                                        .isInstanceOf(ExecutionResult.Rejected.class));
        assertThat(ctx.state().getReviewVerdict()).isNull();
    }

    @Test
    @DisplayName("backtrack records the verdict with its reason and redirects to the target")
    void backtrackRecordsVerdictAndRedirects() {
        var ctx = reviewContext(bothArms(), agentSaid(true));

        var outcome =
                processorDeciding(new ReviewDecision.Backtrack("draft", "add the risk table"))
                        .process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(ctx.state().getCurrentNode()).isEqualTo("draft");
        assertThat(ctx.state().isNodeRedirected()).isTrue();
        assertThat(ctx.state().getReviewVerdict().reason()).isEqualTo("add the risk table");
        assertThat(ctx.state().getContext()).containsEntry(EngineVariables.APPROVED, true);
    }

    @Test
    @DisplayName("review DISABLED records no verdict — the agent's own opinion still routes")
    void disabledRecordsNoVerdict() {
        var ctx =
                reviewContext(
                        bothArms(),
                        agentSaid(false),
                        new ReviewConfig(ReviewMode.DISABLED, false, false));

        var outcome = processorRefusingToBeCalled().process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(ctx.state().getReviewVerdict()).isNull();
        assertThat(ctx.state().getContext()).containsEntry(EngineVariables.APPROVED, false);
    }

    @Test
    @DisplayName("review OPTIONAL on a successful node records no verdict")
    void optionalSuccessRecordsNoVerdict() {
        var ctx =
                reviewContext(
                        bothArms(),
                        agentSaid(false),
                        new ReviewConfig(ReviewMode.OPTIONAL, false, false));

        var outcome = processorRefusingToBeCalled().process(ctx);

        assertThat(outcome).isEqualTo(ProcessorOutcome.CONTINUE);
        assertThat(ctx.state().getReviewVerdict()).isNull();
        assertThat(ctx.state().getContext()).containsEntry(EngineVariables.APPROVED, false);
    }

    // — Fixtures ——————————————————————————————————————————————————————————

    private ReviewPostProcessor processorDeciding(ReviewDecision decision) {
        return new ReviewPostProcessor((_, _, _, _, _, _) -> ReviewOutcome.decided(decision));
    }

    private ReviewPostProcessor processorRefusingToBeCalled() {
        return new ReviewPostProcessor(
                (_, _, _, _, _, _) -> {
                    throw new AssertionError("no reviewer should be consulted");
                });
    }

    private List<TransitionRule> bothArms() {
        return List.of(
                new ApprovalTransition(true, "approved-end"),
                new ApprovalTransition(false, "rejected-end"));
    }

    private Map<String, Object> agentSaid(boolean approved) {
        Map<String, Object> context = new HashMap<>();
        context.put(EngineVariables.APPROVED, approved);
        return context;
    }

    private ProcessorContext reviewContext(
            List<TransitionRule> rules, Map<String, Object> context) {
        return reviewContext(rules, context, new ReviewConfig(ReviewMode.REQUIRED, true, true));
    }

    private ProcessorContext reviewContext(
            List<TransitionRule> rules, Map<String, Object> context, ReviewConfig config) {

        var node =
                StandardNode.builder().id(NODE).transitionRules(rules).reviewConfig(config).build();
        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(NODE)
                        .context(context)
                        .history(new ExecutionHistory())
                        .build();
        var workflow =
                Workflow.builder().id("test-wf").startNode(NODE).nodes(Map.of(NODE, node)).build();
        var execCtx = ExecutionContext.builder().state(state).workflow(workflow).build();
        return new ProcessorContext(
                execCtx, node, new NodeResult(ResultStatus.SUCCESS, "draft output", Map.of()));
    }
}
