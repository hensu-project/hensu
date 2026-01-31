package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;

public non-sealed class AlwaysTransition implements TransitionRule {
    @Override
    public String evaluate(HensuState state, NodeResult result) {
        return "";
    }
}
