package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;

/// Sealed interface for workflow transition rules.
///
/// Transition rules determine the next node to execute based on the current
/// state and node execution result. Rules are evaluated in order after each
/// node completes; the first rule returning a non-null target is used.
///
/// ### Permitted Implementations
/// - {@link AlwaysTransition} - Unconditional transition to target
/// - {@link SuccessTransition} - Transitions on successful execution
/// - {@link FailureTransition} - Transitions on failed execution
/// - {@link ScoreTransition} - Conditional on rubric score thresholds
/// - {@link RubricFailTransition} - Transitions when rubric evaluation fails
///
/// @implNote Implementations must be immutable and stateless. The same rule
/// instance may be evaluated concurrently for parallel workflow branches.
///
/// @see io.hensu.core.workflow.node.Node#getTransitionRules()
/// @see io.hensu.core.execution.WorkflowExecutor for transition evaluation
public sealed interface TransitionRule
        permits AlwaysTransition,
                FailureTransition,
                RubricFailTransition,
                ScoreTransition,
                SuccessTransition {

    /// Evaluates whether this rule applies and returns the target node.
    ///
    /// @param state current workflow execution state, not null
    /// @param result the node execution result to evaluate, not null
    /// @return target node ID if rule applies, null otherwise
    String evaluate(HensuState state, NodeResult result);
}
