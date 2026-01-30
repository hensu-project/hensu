package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;

public record FailureTransition(Integer retryCount, String targetNode) implements TransitionRule {

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        if (result.getStatus() != ResultStatus.FAILURE) {
            return null;
        }
        if (state.getRetryCount() < retryCount) {
            state.incrementRetryCount();
            return state.getCurrentNode(); // Retry same node
        } else {
            return targetNode;
        }
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public String getThenTargetNode() {
        return targetNode;
    }
}
