package io.hensu.core.workflow.node;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.executor.EndNodeExecutor;
import io.hensu.core.execution.result.ExitStatus;
import java.util.Objects;

/// End workflow node representing an end state.
///
/// Ens nodes mark the end of workflow execution with a specific outcome
/// status (SUCCESS, FAILURE, CANCEL).
///
/// @implNote Immutable and thread-safe after construction.
///
/// @see ExitStatus for outcome statuses
/// @see Action for action definitions
/// @see EndNodeExecutor for execution logic
public final class EndNode extends Node {

    private final NodeType nodeType = NodeType.END;
    private final ExitStatus status;

    public EndNode(Builder builder) {
        super(Objects.requireNonNull(builder.id, "Workflow ID required"));
        this.status = Objects.requireNonNull(builder.status, "Exit status required");
    }

    /// Creates a new end node builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the end outcome status.
    ///
    /// @return SUCCESS, FAILURE, or CANCEL, never null
    public ExitStatus getStatus() {
        return status;
    }

    /// Returns the rubric ID (always null for end nodes).
    ///
    /// @return null, end nodes do not support rubric evaluation
    @Override
    public String getRubricId() {
        return null;
    }

    /// Returns the node type for executor dispatch.
    ///
    /// @return END, never null
    @Override
    public NodeType getNodeType() {
        return nodeType;
    }

    /// Returns the end outcome status (alias for {@link #getStatus()}).
    ///
    /// @return SUCCESS, FAILURE, or CANCEL, never null
    public ExitStatus getExitStatus() {
        return status;
    }

    /// Builder for constructing immutable EndNode instances.
    ///
    /// Required fields: `id`, `type`, `actions`
    public static final class Builder {
        private String id;
        public ExitStatus status;

        private Builder() {}

        /// Sets the node identifier (required).
        ///
        /// @param id unique node ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the end outcome status (required).
        ///
        /// @param status SUCCESS, FAILURE, or CANCEL, not null
        /// @return this builder for chaining
        public Builder status(ExitStatus status) {
            this.status = status;
            return this;
        }

        /// Builds the immutable end node.
        ///
        /// @return new EndNode instance, never null
        /// @throws NullPointerException if required fields are null
        public EndNode build() {
            return new EndNode(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndNode node)) return false;
        return Objects.equals(id, node.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "EndNode{id='" + id + "'}";
    }
}
