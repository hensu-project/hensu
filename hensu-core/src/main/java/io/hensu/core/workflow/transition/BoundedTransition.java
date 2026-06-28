package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import java.util.Set;

/// Decorates any transition trigger with a per-node retry budget and an escalation target.
///
/// When the inner trigger fires, routes to the inner target while the namespaced counter
/// ({@code namespace:nodeId}) is under budget, then escalates to {@code otherwise}. Evaluation
/// is pure – the counter increment happens in {@code TransitionPostProcessor} after the rule
/// matches, using the same {@link #underBudget(HensuState)} predicate (single source of truth).
///
/// ### Namespaces
/// Each trigger kind uses its own namespace to isolate budgets:
/// - {@code failure} – agent execution failures (retry)
/// - {@code consensus} – parallel-node consensus failures (revise)
/// - {@code approval} – reviewer rejections (revise)
/// - {@code score} – rubric score below threshold (revise)
///
/// @param inner                    the trigger rule supplying the condition and the revise/retry
///                                 target
/// @param namespace                counter namespace isolating budgets per trigger kind
/// @param budget                   maximum attempts before escalation (must be positive)
/// @param otherwise                escalation node when the budget is exhausted, not null
/// @param escalationWithFeedback   when true, recommendation survives the escalation transition
///                                 (over budget)
/// @see TransitionRule#trigger() for unwrapping to the inner rule
/// @see TransitionRule#requiredEngineVars() for engine-variable delegation
public record BoundedTransition(
        TransitionRule inner,
        String namespace,
        int budget,
        String otherwise,
        boolean escalationWithFeedback)
        implements TransitionRule {

    /// Creates a bounded transition without escalation feedback preservation.
    public BoundedTransition(TransitionRule inner, String namespace, int budget, String otherwise) {
        this(inner, namespace, budget, otherwise, false);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        String target = inner.evaluate(state, result);
        if (target == null) return null;
        return underBudget(state) ? target : otherwise;
    }

    /// Single source of truth for the budget-check predicate – shared by {@code evaluate()}
    /// and {@code TransitionPostProcessor.applyTransitionEffects} so the two can never diverge.
    ///
    /// @param state current workflow state providing the retry counter map
    /// @return true if the counter for this namespace and current node is below the budget
    public boolean underBudget(HensuState state) {
        return state.getRetryCount(namespace, state.getCurrentNode()) < budget;
    }

    @Override
    public boolean withFeedback() {
        return escalationWithFeedback;
    }

    @Override
    public Set<String> requiredEngineVars() {
        return inner.requiredEngineVars();
    }

    @Override
    public TransitionRule trigger() {
        return inner.trigger();
    }
}
