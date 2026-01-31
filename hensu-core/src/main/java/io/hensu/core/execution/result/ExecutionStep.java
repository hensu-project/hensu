package io.hensu.core.execution.result;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuSnapshot;
import java.time.Instant;
import java.util.Objects;

/// Records a single step in workflow execution history.
///
/// Captures the executed node, its result, and a snapshot of the workflow
/// state at that point. Used for time-travel debugging and execution analysis.
///
/// ### Contracts
/// - **Precondition**: `nodeId` and `result` must be provided
/// - **Postcondition**: Immutable after construction
///
/// @see ExecutionHistory for step collection
/// @see HensuSnapshot for state snapshots
public class ExecutionStep {

    private final String nodeId;
    private final NodeResult result;
    private final Instant timestamp;
    private final HensuSnapshot snapshot;

    /// Constructs an execution step from builder values.
    ///
    /// @param builder the builder containing step values, not null
    /// @throws NullPointerException if nodeId or result is null
    public ExecutionStep(Builder builder) {
        this.nodeId = Objects.requireNonNull(builder.nodeId, "Node ID required");
        this.result = Objects.requireNonNull(builder.result, "Result required");
        this.timestamp = builder.timestamp;
        this.snapshot = builder.snapshot;
    }

    /// Constructs an execution step with all values.
    ///
    /// @param nodeId identifier of the executed node, not null
    /// @param snapshot state snapshot at execution time, may be null
    /// @param result the node execution result, not null
    /// @param timestamp when execution completed, may be null
    public ExecutionStep(
            String nodeId, HensuSnapshot snapshot, NodeResult result, Instant timestamp) {
        this.nodeId = nodeId;
        this.snapshot = snapshot;
        this.result = result;
        this.timestamp = timestamp;
    }

    /// Creates a new builder for constructing ExecutionStep instances.
    ///
    /// @return a new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the identifier of the executed node.
    ///
    /// @return the node ID, not null
    public String getNodeId() {
        return nodeId;
    }

    /// Returns the execution result for this step.
    ///
    /// @return the node result, not null
    public NodeResult getResult() {
        return result;
    }

    /// Returns when execution completed.
    ///
    /// @return the completion timestamp, may be null
    public Instant getTimestamp() {
        return timestamp;
    }

    /// Returns the state snapshot at execution time.
    ///
    /// @return the state snapshot, may be null
    public HensuSnapshot getSnapshot() {
        return snapshot;
    }

    /// Builder for constructing {@link ExecutionStep} instances.
    public static final class Builder {
        private String nodeId;
        private NodeResult result;
        private Instant timestamp;
        private HensuSnapshot snapshot;

        private Builder() {}

        /// Sets the node identifier.
        ///
        /// @param nodeId the executed node ID, not null
        /// @return this builder for chaining
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /// Sets the execution result.
        ///
        /// @param result the node execution result, not null
        /// @return this builder for chaining
        public Builder result(NodeResult result) {
            this.result = result;
            return this;
        }

        /// Sets the completion timestamp.
        ///
        /// @param timestamp when execution completed, may be null
        /// @return this builder for chaining
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /// Sets the state snapshot.
        ///
        /// @param snapshot state at execution time, may be null
        /// @return this builder for chaining
        public Builder snapshot(HensuSnapshot snapshot) {
            this.snapshot = snapshot;
            return this;
        }

        /// Builds the ExecutionStep instance.
        ///
        /// @return a new ExecutionStep, never null
        /// @throws NullPointerException if nodeId or result is null
        public ExecutionStep build() {
            return new ExecutionStep(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExecutionStep step)) return false;
        return Objects.equals(nodeId, step.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "ExecutionStep{nodeId='" + nodeId + "'}";
    }
}
