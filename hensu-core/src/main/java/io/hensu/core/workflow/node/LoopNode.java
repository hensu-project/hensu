package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.BreakRule;
import io.hensu.core.workflow.transition.LoopCondition;

/// Immutable LoopNode definition. Pure data structure with no dependencies.
public final class LoopNode extends Node {
    private final NodeType nodeType = NodeType.LOOP;

    public LoopNode(String id) {
        super(id);
    }

    @Override
    public String getRubricId() {
        return "";
    }

    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    public int getMaxIterations() {
        return 0;
    }

    public Node getBodyNode() {
        return null;
    }

    public BreakRule[] getBreakRules() {
        return null;
    }

    public LoopCondition getCondition() {
        return null;
    }
}
