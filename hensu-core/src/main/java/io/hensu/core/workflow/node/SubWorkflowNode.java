package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SubWorkflowNode extends Node {

    private final NodeType nodeType = NodeType.SUB_WORKFLOW;
    private final String workflowId;
    private final Map<String, String> inputMapping;
    private final Map<String, String> outputMapping;
    private final List<TransitionRule> transitionRules;

    public SubWorkflowNode(
            String id,
            String workflowId,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping,
            List<TransitionRule> transitionRules) {
        super(id);
        this.workflowId = workflowId;
        this.inputMapping = inputMapping;
        this.outputMapping = outputMapping;
        this.transitionRules = transitionRules;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public Map<String, String> getInputMapping() {
        return inputMapping;
    }

    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    @Override
    public String getRubricId() {
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (SubWorkflowNode) obj;
        return Objects.equals(this.id, that.id)
                && Objects.equals(this.workflowId, that.workflowId)
                && Objects.equals(this.inputMapping, that.inputMapping)
                && Objects.equals(this.outputMapping, that.outputMapping)
                && Objects.equals(this.transitionRules, that.transitionRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, workflowId, inputMapping, outputMapping, transitionRules);
    }

    @Override
    public String toString() {
        return "SubWorkflowNode["
                + "id="
                + id
                + ", "
                + "workflowId="
                + workflowId
                + ", "
                + "inputMapping="
                + inputMapping
                + ", "
                + "outputMapping="
                + outputMapping
                + ", "
                + "transitionRules="
                + transitionRules
                + ']';
    }
}
