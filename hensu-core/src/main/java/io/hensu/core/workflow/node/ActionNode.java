package io.hensu.core.workflow.node;

import io.hensu.core.execution.action.Action;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.List;
import java.util.Objects;

/// Action workflow node for executing commands mid-workflow.
///
/// Action nodes execute side effects like shell commands, HTTP calls, or
/// notifications at any point in the workflow, then transition to the next node.
/// Unlike end nodes, action nodes continue workflow execution.
///
/// @implNote Immutable and thread-safe after construction.
///
/// @see Action for action definitions
/// @see io.hensu.core.execution.executor.ActionNodeExecutor for execution logic
public final class ActionNode extends Node {

    private final NodeType nodeType = NodeType.ACTION;
    private final List<Action> actions;
    private final List<TransitionRule> transitionRules;

    public ActionNode(Builder builder) {
        super(Objects.requireNonNull(builder.id, "Node ID required"));
        this.actions = Objects.requireNonNull(builder.actions, "Actions required");
        this.transitionRules =
                Objects.requireNonNull(builder.transitionRules, "Transition rules required");
    }

    /// Creates a new action node builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the actions to execute.
    ///
    /// @return unmodifiable list of actions, never null
    public List<Action> getActions() {
        return actions;
    }

    /// Returns the rubric ID (always empty for action nodes).
    ///
    /// @return empty string, never null
    @Override
    public String getRubricId() {
        return "";
    }

    /// Returns the node type for executor dispatch.
    ///
    /// @return NodeType.ACTION, never null
    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    /// Returns the transition rules for next node selection.
    ///
    /// @return unmodifiable list of rules, never null
    @Override
    public List<TransitionRule> getTransitionRules() {
        return transitionRules;
    }

    /// Builder for constructing immutable ActionNode instances.
    ///
    /// Required fields: `id`, `actions`, `transitionRules`
    public static final class Builder {
        private String id;
        private List<Action> actions;
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

        /// Sets the actions to execute (required).
        ///
        /// @param actions list of actions, not null
        /// @return this builder for chaining
        public Builder actions(List<Action> actions) {
            this.actions = List.copyOf(actions);
            return this;
        }

        /// Sets the transition rules (required).
        ///
        /// @param transitionRules rules for next node selection, not null
        /// @return this builder for chaining
        public Builder transitionRules(List<TransitionRule> transitionRules) {
            this.transitionRules = List.copyOf(transitionRules);
            return this;
        }

        /// Builds the immutable action node.
        ///
        /// @return new ActionNode instance, never null
        /// @throws NullPointerException if required fields are null
        public ActionNode build() {
            return new ActionNode(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActionNode node)) return false;
        return Objects.equals(id, node.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ActionNode{id='" + id + "', actions=" + actions.size() + "}";
    }
}
