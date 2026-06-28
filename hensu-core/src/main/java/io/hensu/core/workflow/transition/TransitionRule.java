package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import java.util.Set;

/// Sealed interface for workflow transition rules.
///
/// Transition rules determine the next node to execute based on the current
/// state and node execution result. Rules are evaluated in order after each
/// node completes; the first rule returning a non-null target is used.
///
/// ### Permitted Implementations
/// - {@link AlwaysTransition} – unconditional transition to target
/// - {@link SuccessTransition} – transitions on successful execution
/// - {@link FailureTransition} – transitions on failed execution (retry)
/// - {@link NoConsensusTransition} – transitions when a parallel node reaches no consensus
/// - {@link ScoreTransition} – conditional on rubric score thresholds
/// - {@link RubricFailTransition} – transitions when rubric evaluation fails
/// - {@link ApprovalTransition} – conditional on the `approved` boolean engine variable
/// - {@link BoundedTransition} – decorates any trigger with a per-node retry budget and
///   an escalation target
///
/// ### Capability Methods
/// Engine components that need to know *what* a rule routes on MUST call
/// {@link #requiredEngineVars()} – never {@code instanceof}. This keeps decorators
/// (e.g. {@link BoundedTransition}) transparent by construction.
///
/// @implNote Implementations must be immutable and stateless. The same rule
/// instance may be evaluated concurrently for parallel workflow branches.
///
/// @see io.hensu.core.workflow.node.Node#getTransitionRules()
/// @see io.hensu.core.execution.WorkflowExecutor for transition evaluation
public sealed interface TransitionRule
        permits AlwaysTransition,
                ApprovalTransition,
                BoundedTransition,
                FailureTransition,
                NoConsensusTransition,
                RubricFailTransition,
                ScoreTransition,
                SuccessTransition {

    /// Evaluates whether this rule applies and returns the target node.
    ///
    /// @param state current workflow execution state, not null
    /// @param result the node execution result to evaluate, not null
    /// @return target node ID if rule applies, null otherwise
    String evaluate(HensuState state, NodeResult result);

    /// Returns the engine variables this rule routes on, which the engine must
    /// instruct the agent to produce and extract from its output. Decorators
    /// delegate to their inner rule. Empty for rules that route on execution
    /// status alone.
    ///
    /// Engine components (prompt injectors, output extraction) MUST consume this
    /// declaration – never {@code instanceof} on rule types – so decorated rules
    /// keep their semantics.
    ///
    /// @return the engine variable names this rule depends on, never null
    default Set<String> requiredEngineVars() {
        return Set.of();
    }

    /// Returns whether this transition preserves the
    /// {@link io.hensu.core.execution.EngineVariables#RECOMMENDATION}
    /// value across the transition, allowing the target node to see feedback from
    /// the source node's evaluation.
    ///
    /// When {@code true}, {@link io.hensu.core.execution.pipeline.TransitionPostProcessor}
    /// clears routing variables ({@code score}, {@code approved}) but keeps
    /// {@code recommendation} in the state context. When {@code false} (default),
    /// all engine variables are cleared on forward transitions.
    ///
    /// Records that declare a {@code boolean withFeedback} component override this
    /// method automatically via the generated accessor – no explicit override needed.
    ///
    /// @return true if recommendation should survive this transition
    default boolean withFeedback() {
        return false;
    }

    /// Returns the underlying trigger rule. Decorators return their wrapped rule;
    /// plain rules return themselves. Use for components that must evaluate or label
    /// the inner rule (rubric matching, visualization). For engine-variable wiring
    /// prefer {@link #requiredEngineVars()}.
    ///
    /// @return the leaf trigger rule, never null
    default TransitionRule trigger() {
        return this;
    }
}
