package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;

/// Unconditional transition that always fires.
///
/// @param withFeedback when true, recommendation survives this transition
public record AlwaysTransition(boolean withFeedback) implements TransitionRule {

    /// Creates an always transition without feedback preservation.
    public AlwaysTransition() {
        this(false);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        return "";
    }
}
