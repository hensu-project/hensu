package io.hensu.core.workflow.transition;

public record BreakRule(LoopCondition condition, String targetNode) {
    public LoopCondition getCondition() {
        return condition;
    }

    public String getTargetNode() {
        return targetNode;
    }
}
