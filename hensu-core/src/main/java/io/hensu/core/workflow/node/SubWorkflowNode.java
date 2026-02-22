package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Sub-workflow node that delegates execution to a nested workflow.
///
/// Embeds a full workflow as a single node. Input context variables are
/// mapped into the sub-workflow's context; output variables are mapped back
/// on completion.
///
/// @implNote Immutable and thread-safe after construction.
public final class SubWorkflowNode extends Node {

    private final NodeType nodeType = NodeType.SUB_WORKFLOW;
    private final String workflowId;
    private final Map<String, String> inputMapping;
    private final Map<String, String> outputMapping;
    private final List<TransitionRule> transitionRules;

    private SubWorkflowNode(Builder builder) {
        super(builder.id);
        this.workflowId = builder.workflowId;
        this.inputMapping =
                builder.inputMapping != null ? Map.copyOf(builder.inputMapping) : Map.of();
        this.outputMapping =
                builder.outputMapping != null ? Map.copyOf(builder.outputMapping) : Map.of();
        this.transitionRules =
                builder.transitionRules != null ? List.copyOf(builder.transitionRules) : List.of();
    }

    /// Creates a new sub-workflow node builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the identifier of the nested workflow to execute.
    ///
    /// @return workflow ID, never null
    public String getWorkflowId() {
        return workflowId;
    }

    /// Returns the input variable mapping from parent context to sub-workflow context.
    ///
    /// Keys are parent context keys; values are sub-workflow context keys.
    ///
    /// @return unmodifiable mapping, never null (may be empty)
    public Map<String, String> getInputMapping() {
        return inputMapping;
    }

    /// Returns the output variable mapping from sub-workflow context back to parent context.
    ///
    /// Keys are sub-workflow context keys; values are parent context keys.
    ///
    /// @return unmodifiable mapping, never null (may be empty)
    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    /// Returns the transition rules for next node selection.
    ///
    /// @return unmodifiable list of rules, never null (may be empty)
    @Override
    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    /// Returns the node type for executor dispatch.
    ///
    /// @return SUB_WORKFLOW, never null
    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    /// Returns the rubric ID (always null for sub-workflow nodes).
    ///
    /// @return null, sub-workflow nodes do not support rubric evaluation
    @Override
    public String getRubricId() {
        return null;
    }

    /// Builder for constructing immutable SubWorkflowNode instances.
    ///
    /// Required fields: `id`, `workflowId`
    public static final class Builder {
        private String id;
        private String workflowId;
        private Map<String, String> inputMapping;
        private Map<String, String> outputMapping;
        private List<TransitionRule> transitionRules;

        private Builder() {}

        /// Sets the node identifier (required).
        ///
        /// @param id unique node ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the nested workflow identifier (required).
        ///
        /// @param workflowId ID of the workflow to invoke, not null
        /// @return this builder for chaining
        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        /// Sets the input variable mapping from parent to sub-workflow context.
        ///
        /// @param inputMapping key mapping, may be null (treated as empty)
        /// @return this builder for chaining
        public Builder inputMapping(Map<String, String> inputMapping) {
            this.inputMapping = inputMapping;
            return this;
        }

        /// Sets the output variable mapping from sub-workflow to parent context.
        ///
        /// @param outputMapping key mapping, may be null (treated as empty)
        /// @return this builder for chaining
        public Builder outputMapping(Map<String, String> outputMapping) {
            this.outputMapping = outputMapping;
            return this;
        }

        /// Sets the transition rules for next node selection.
        ///
        /// @param transitionRules rules for next node selection, may be null (treated as empty)
        /// @return this builder for chaining
        public Builder transitionRules(List<TransitionRule> transitionRules) {
            this.transitionRules = transitionRules;
            return this;
        }

        /// Builds the immutable sub-workflow node.
        ///
        /// @return new SubWorkflowNode instance, never null
        /// @throws IllegalStateException if `id` or `workflowId` is null or blank
        public SubWorkflowNode build() {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("SubWorkflowNode id is required");
            }
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalStateException("SubWorkflowNode workflowId is required");
            }
            return new SubWorkflowNode(this);
        }
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
