package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.transition.Condition.Equals;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitionRuleChecksTest {

    @Test
    void shouldDetectBoundedRulesSharingRetryCounter() {
        // Two bounded arms with the same namespace share one retry counter: the
        // second arm's budget is silently consumed by the first. Must be a build
        // error, distinct namespaces must pass.
        List<TransitionRule> shared =
                List.of(
                        new BoundedTransition(new FailureTransition(null), "revise", 3, "esc-a"),
                        new BoundedTransition(
                                new ApprovalTransition(false, "write"), "revise", 2, "esc-b"));
        List<TransitionRule> distinct =
                List.of(
                        new BoundedTransition(new FailureTransition(null), "failure", 3, "esc-a"),
                        new BoundedTransition(
                                new ApprovalTransition(false, "write"), "approval", 2, "esc-b"));

        assertThat(TransitionRuleChecks.duplicateBoundedNamespaces(shared))
                .containsExactly("revise");
        assertThat(TransitionRuleChecks.duplicateBoundedNamespaces(distinct)).isEmpty();
    }

    @Test
    void shouldWarnWhenExitArmIsShadowedByBoundedArm() {
        // First match wins: an exit arm declared after a bounded self-revise arm
        // cannot fire until the budget is exhausted — the workflow loops N times
        // even when the agent reports completion on iteration 1.
        var boundedRevise =
                new BoundedTransition(
                        new ConditionTransition("status", new Equals("incomplete"), "worker"),
                        "condition",
                        5,
                        "escalate");
        var exitArm = new ConditionTransition("status", new Equals("complete"), "deploy");

        assertThat(
                        TransitionRuleChecks.boundedBeforeExitWarning(
                                List.of(boundedRevise, exitArm), "worker"))
                .hasValueSatisfying(msg -> assertThat(msg).contains("worker"));

        // exit-first ordering is the correct shape — no warning
        assertThat(
                        TransitionRuleChecks.boundedBeforeExitWarning(
                                List.of(exitArm, boundedRevise), "worker"))
                .isEmpty();
    }

    @Test
    void shouldNotWarnWhenRuleAfterBoundedArmTargetsSameNode() {
        // A ScoreTransition arm routing back to the node itself is part of the
        // loop, not a shadowed exit — warning here would train users to ignore it.
        var bounded = new BoundedTransition(new FailureTransition(null), "failure", 3, "escalate");
        var selfRoute =
                new ScoreTransition(
                        List.of(new ScoreCondition(ComparisonOperator.LT, 70.0, null, "worker")));

        assertThat(
                        TransitionRuleChecks.boundedBeforeExitWarning(
                                List.of(bounded, selfRoute), "worker"))
                .isEmpty();
    }
}
