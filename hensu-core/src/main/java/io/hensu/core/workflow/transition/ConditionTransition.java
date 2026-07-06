package io.hensu.core.workflow.transition;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import java.util.Map;
import java.util.Set;

/// Transition rule that routes on an arbitrary declared output variable.
///
/// Reads the variable from the execution context (populated by
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} from the
/// agent's JSON response) and applies a single typed {@link Condition} predicate.
/// The general loop-exit primitive: a node revising itself under a
/// {@link BoundedTransition} exits the loop when e.g. `status == "complete"`.
///
/// ### Composition is OR-only
/// One variable, one predicate per rule; multiple rules compose as ordered arms
/// where the first match wins. A conjunction such as
/// `status == "complete" AND score >= 80` is deliberately inexpressible – have the
/// agent emit a combined variable instead (e.g. `done`).
///
/// ### Type mismatches are loud
/// A value that cannot be coerced to the predicate's expected form (absent
/// variable, non-numeric string under a numeric operator, structured JSON object)
/// yields no match *and* a diagnostic via {@link #mismatchDiagnostic(HensuState)},
/// which the engine surfaces through
/// {@link io.hensu.core.execution.ExecutionListener#onTransitionWarning(String, String)}.
/// A mismatch is never a silent `false`.
///
/// @param variable name of the declared writes variable to route on, not blank
/// @param condition typed predicate applied to the variable's value, not null
/// @param targetNode node to transition to when the predicate matches, not null
/// @param withFeedback when true, recommendation survives this transition
/// @see Condition for the predicate vocabulary and coercion contract
/// @see TransitionRule for transition evaluation contract
public record ConditionTransition(
        String variable, Condition condition, String targetNode, boolean withFeedback)
        implements TransitionRule {

    /// Creates a condition transition without feedback preservation.
    public ConditionTransition(String variable, Condition condition, String targetNode) {
        this(variable, condition, targetNode, false);
    }

    public ConditionTransition {
        if (variable == null || variable.isBlank()) {
            throw new IllegalArgumentException("variable must not be blank");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        if (targetNode == null || targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode must not be blank");
        }
    }

    /// Declares the routed variable plus `recommendation`, so output extraction
    /// captures both from the agent's JSON response and the feedback pipeline can
    /// carry the agent's justification into retry prompts.
    @Override
    public Set<String> requiredEngineVars() {
        return Set.of(variable, EngineVariables.RECOMMENDATION);
    }

    /// Routes to the target node when the node succeeded and the predicate matches.
    ///
    /// Only {@link ResultStatus#SUCCESS} results are evaluated — a failed node never
    /// produced the routed variable, so it falls through to a later
    /// {@link FailureTransition} instead of tripping a type-mismatch diagnostic.
    ///
    /// @param state current workflow state holding the routed variable, not null
    /// @param result the node execution result, not null
    /// @return target node ID if result is SUCCESS and the predicate matches, null otherwise
    @Override
    public String evaluate(HensuState state, NodeResult result) {
        if (result.getStatus() != ResultStatus.SUCCESS) {
            return null;
        }
        return condition.test(contextValue(state)) == Condition.MatchResult.MATCH
                ? targetNode
                : null;
    }

    @Override
    public String mismatchDiagnostic(HensuState state) {
        Object value = contextValue(state);
        if (condition.test(value) != Condition.MatchResult.TYPE_MISMATCH) {
            return null;
        }
        String actual =
                value == null ? "<absent>" : value.getClass().getSimpleName() + " '" + value + "'";
        return "Condition on variable '"
                + variable
                + "' ("
                + condition.describe()
                + ") could not be evaluated: actual value is "
                + actual;
    }

    private Object contextValue(HensuState state) {
        Map<String, Object> context = state.getContext();
        return context == null ? null : context.get(variable);
    }
}
