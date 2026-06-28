package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.parallel.ConsensusResult.Vote;
import io.hensu.core.execution.parallel.ConsensusResult.VoteType;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.plan.Plan;
import io.hensu.core.plan.PlannedStep;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.BoundedTransition;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.NoConsensusTransition;
import io.hensu.core.workflow.transition.ScoreTransition;
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

    // — Error paths ———————————————————————————————————————————————————————

    @Test
    @DisplayName("throws when no transition matches — catches missing-transition misconfiguration")
    void shouldThrowWhenNoTransitionMatches() {
        var ctx = contextWithTransitions("orphan", List.of());

        assertThatThrownBy(() -> processor.process(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan");
    }

    @Test
    @DisplayName("LoopNode without loop_exit_target throws — LoopNode has no fallback transitions")
    void shouldThrowWhenLoopNodeHasNoExitTarget() {
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
        var ctx = loopNodeContext();
        ctx.state().setLoopBreakTarget("break-target");
        ctx.state().getContext().put("loop_exit_target", "exit-node");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("break-target");
        assertThat(ctx.state().getLoopBreakTarget()).isNull();
    }

    // — Loop break override (StandardNode) ———————————————————————————————

    @Test
    @DisplayName("loopBreakTarget overrides normal transition on StandardNode and is consumed")
    void shouldPrioritizeLoopBreakOverRuleOnStandardNode() {
        var ctx = contextWithTransitions("node", List.of(new SuccessTransition("rule-target")));
        ctx.state().setLoopBreakTarget("override-target");

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("override-target");
        assertThat(ctx.state().getLoopBreakTarget()).isNull();
    }

    // — Backtrack skip ————————————————————————————————————————————————————

    @Test
    @DisplayName("skips transition when nodeRedirected flag is set by a prior processor")
    void shouldSkipWhenPriorProcessorAlreadyRedirected() {
        var ctx = contextWithTransitions("original", List.of(new SuccessTransition("normal-next")));
        ctx.state().setCurrentNode("already-redirected");
        ctx.state().setNodeRedirected(true);

        processor.process(ctx);

        assertThat(ctx.state().getCurrentNode()).isEqualTo("already-redirected");
        assertThat(ctx.state().isNodeRedirected()).isFalse();
    }

    // — Active plan clearing ————————————————————————————————————————————

    @Test
    @DisplayName("clears activePlan when transitioning to a different node")
    void shouldClearActivePlanOnTransition() {
        var ctx = contextWithTransitions("node-a", List.of(new SuccessTransition("node-b")));
        Plan plan =
                Plan.staticPlan(
                        "node-a", List.of(PlannedStep.pending(0, "tool", Map.of(), "step")));
        ctx.state().setActivePlan(plan);

        processor.process(ctx);

        assertThat(ctx.state().getActivePlan()).isNull();
    }

    // — Engine variable lifecycle ————————————————————————————————————————

    @Nested
    @DisplayName("Engine variable lifecycle")
    class EngineVarLifecycle {

        @Test
        @DisplayName("forward transition clears all engine vars from context")
        void forwardClearsAllEngineVars() {
            var ctx = contextWithTransitions("node", List.of(new SuccessTransition("next")));
            seedEngineVars(ctx);

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .doesNotContainKeys(
                            EngineVariables.SCORE,
                            EngineVariables.APPROVED,
                            EngineVariables.RECOMMENDATION);
        }

        @Test
        @DisplayName(
                "forward transition with withFeedback keeps recommendation, clears routing vars")
        void forwardWithFeedbackKeepsRecommendation() {
            var rule = new SuccessTransition("next", true);
            var ctx = contextWithTransitions("node", List.of(rule));
            seedEngineVars(ctx);

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .doesNotContainKeys(EngineVariables.SCORE, EngineVariables.APPROVED)
                    .containsEntry(EngineVariables.RECOMMENDATION, "improve section 2");
        }

        @Test
        @DisplayName("score transition with withFeedback preserves recommendation on forward")
        void scoreWithFeedbackPreservesRecommendation() {
            var rule =
                    new ScoreTransition(
                            List.of(new ScoreCondition(ComparisonOperator.LT, 70.0, null, "write")),
                            true);
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.SCORE, 50.0);
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "needs more detail");

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .doesNotContainKey(EngineVariables.SCORE)
                    .containsEntry(EngineVariables.RECOMMENDATION, "needs more detail");
        }

        @Test
        @DisplayName("approval transition with withFeedback preserves recommendation on forward")
        void approvalWithFeedbackPreservesRecommendation() {
            var rule = new ApprovalTransition(true, "next", true);
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, true);
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "well structured");

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .doesNotContainKey(EngineVariables.APPROVED)
                    .containsEntry(EngineVariables.RECOMMENDATION, "well structured");
        }

        @Test
        @DisplayName("bounded backtrack keeps recommendation for non-failure inner")
        void boundedBacktrackKeepsRecommendation() {
            var inner = new ApprovalTransition(false, "write");
            var rule = new BoundedTransition(inner, "approval", 3, "escalate");
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, false);
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "rewrite intro");

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .doesNotContainKeys(EngineVariables.SCORE, EngineVariables.APPROVED)
                    .containsEntry(EngineVariables.RECOMMENDATION, "rewrite intro");
        }

        @Test
        @DisplayName("bounded backtrack clears recommendation for failure inner")
        void boundedBacktrackClearsRecommendationForFailure() {
            var inner = new FailureTransition(null);
            var rule = new BoundedTransition(inner, "failure", 3, "error");
            var ctx =
                    contextWithTransitionsAndResult(
                            "node", List.of(rule), NodeResult.failure("timeout"));
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "stale");

            processor.process(ctx);

            assertThat(ctx.state().getContext()).doesNotContainKey(EngineVariables.RECOMMENDATION);
        }

        @Test
        @DisplayName("bounded escalation with escalationWithFeedback keeps recommendation")
        void boundedEscalationWithFeedbackKeepsRecommendation() {
            var inner = new ApprovalTransition(false, "write");
            var rule = new BoundedTransition(inner, "approval", 1, "escalate", true);
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, false);
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "final feedback");
            ctx.state().incrementRetryCount("approval", "review");

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("escalate");
            assertThat(ctx.state().getContext())
                    .doesNotContainKeys(EngineVariables.SCORE, EngineVariables.APPROVED)
                    .containsEntry(EngineVariables.RECOMMENDATION, "final feedback");
        }

        @Test
        @DisplayName("bounded escalation without feedback clears all engine vars")
        void boundedEscalationWithoutFeedbackClearsAll() {
            var inner = new ApprovalTransition(false, "write");
            var rule = new BoundedTransition(inner, "approval", 1, "escalate");
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, false);
            ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "gone");
            ctx.state().incrementRetryCount("approval", "review");

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("escalate");
            assertThat(ctx.state().getContext())
                    .doesNotContainKeys(
                            EngineVariables.SCORE,
                            EngineVariables.APPROVED,
                            EngineVariables.RECOMMENDATION);
        }
    }

    // — Bounded retry counters ———————————————————————————————————————————

    @Nested
    @DisplayName("Bounded retry counters")
    class BoundedRetryCounters {

        @Test
        @DisplayName("bounded backtrack increments retry counter")
        void backtrackIncrementsCounter() {
            var inner = new ApprovalTransition(false, "write");
            var rule = new BoundedTransition(inner, "approval", 3, "escalate");
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, false);

            processor.process(ctx);

            assertThat(ctx.state().getRetryCount("approval", "review")).isEqualTo(1);
        }

        @Test
        @DisplayName("bounded escalation resets retry counters for the node")
        void escalationResetsCounters() {
            var inner = new ApprovalTransition(false, "write");
            var rule = new BoundedTransition(inner, "approval", 1, "escalate");
            var ctx = contextWithTransitions("review", List.of(rule));
            ctx.state().getContext().put(EngineVariables.APPROVED, false);
            ctx.state().incrementRetryCount("approval", "review");

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("escalate");
            assertThat(ctx.state().getRetryCount("approval", "review")).isZero();
        }

        @Test
        @DisplayName("forward transition resets retry counters for the node")
        void forwardResetsCounters() {
            var ctx = contextWithTransitions("node", List.of(new SuccessTransition("next")));
            ctx.state().incrementRetryCount("failure", "node");
            ctx.state().incrementRetryCount("failure", "node");

            processor.process(ctx);

            assertThat(ctx.state().getRetryCount("failure", "node")).isZero();
        }
    }

    // — Consensus feedback injection —————————————————————————————————————

    @Nested
    @DisplayName("Consensus feedback injection")
    class ConsensusFeedbackInjection {

        @Test
        @DisplayName("injects vote details into recommendation on no-consensus backtrack")
        void injectsVoteFeedbackOnNoConsensusBacktrack() {
            var inner = new NoConsensusTransition("producer");
            var rule = new BoundedTransition(inner, "consensus", 3, "escalate");
            var votes =
                    Map.of(
                            "branch-a",
                            new Vote(
                                    "branch-a", "agent-a", VoteType.APPROVE, 80.0, 1.0, "output-a"),
                            "branch-b",
                            new Vote(
                                    "branch-b", "agent-b", VoteType.REJECT, 40.0, 1.0, "output-b"));
            var consensus =
                    new ConsensusResult(
                            false, ConsensusStrategy.MAJORITY_VOTE, List.of(), null, votes, null);
            var result =
                    NodeResult.builder()
                            .status(ResultStatus.FAILURE)
                            .output("no consensus")
                            .metadata(new HashMap<>(Map.of("consensus_reached", false)))
                            .build();
            var ctx = contextWithTransitionsAndResult("parallel", List.of(rule), result);
            ctx.state().getContext().put("consensus_result:parallel", consensus);

            processor.process(ctx);

            String recommendation =
                    (String) ctx.state().getContext().get(EngineVariables.RECOMMENDATION);
            assertThat(recommendation).isNotBlank();
            assertThat(recommendation).contains("branch-a", "branch-b");
        }

        @Test
        @DisplayName("injects judge reasoning on JUDGE_DECIDES no-consensus backtrack")
        void injectsJudgeReasoningOnNoConsensusBacktrack() {
            var inner = new NoConsensusTransition("producer");
            var rule = new BoundedTransition(inner, "consensus", 3, "escalate");
            var result =
                    NodeResult.builder()
                            .status(ResultStatus.FAILURE)
                            .output("no consensus")
                            .metadata(new HashMap<>(Map.of("consensus_reached", false)))
                            .build();
            var ctx = contextWithTransitionsAndResult("parallel", List.of(rule), result);
            ctx.state()
                    .getContext()
                    .put(
                            "consensus_result:parallel",
                            new ConsensusResult(
                                    false,
                                    ConsensusStrategy.JUDGE_DECIDES,
                                    List.of(),
                                    null,
                                    Map.of(),
                                    "branches disagree on tone"));

            processor.process(ctx);

            assertThat(ctx.state().getContext().get(EngineVariables.RECOMMENDATION))
                    .isEqualTo("branches disagree on tone");
        }
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private void seedEngineVars(ProcessorContext ctx) {
        ctx.state().getContext().put(EngineVariables.SCORE, 85.0);
        ctx.state().getContext().put(EngineVariables.APPROVED, true);
        ctx.state().getContext().put(EngineVariables.RECOMMENDATION, "improve section 2");
    }

    private ProcessorContext contextWithTransitions(String nodeId, List<TransitionRule> rules) {
        return contextWithTransitionsAndResult(
                nodeId, rules, NodeResult.success("output", Map.of()));
    }

    private ProcessorContext contextWithTransitionsAndResult(
            String nodeId, List<TransitionRule> rules, NodeResult result) {
        Node node = StandardNode.builder().id(nodeId).transitionRules(rules).build();
        return buildContext(nodeId, node, result);
    }

    private ProcessorContext loopNodeContext() {
        LoopNode loopNode = new LoopNode("loop");
        return buildContext("loop", loopNode, NodeResult.success("output", Map.of()));
    }

    private ProcessorContext buildContext(String nodeId, Node node, NodeResult result) {
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
        return new ProcessorContext(execCtx, node, result);
    }
}
