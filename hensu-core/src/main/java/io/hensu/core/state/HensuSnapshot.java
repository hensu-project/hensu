package io.hensu.core.state;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.plan.Plan;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Immutable snapshot of workflow state for persistence and resume.
///
/// Captures the complete workflow execution state at a point in time, enabling:
/// - Checkpoint-based persistence for long-running workflows
/// - Resume from server restart or failure
/// - State transfer between server instances (multi-tenancy)
/// - Time-travel debugging via execution history
///
/// ### Contracts
/// - **Precondition**: `workflowId` and `executionId` must not be null
/// - **Postcondition**: All fields immutable after construction
///
/// ### Usage
/// {@snippet :
/// // Create snapshot from current state
/// HensuSnapshot snapshot = HensuSnapshot.from(state, "user-requested-pause");
///
/// // Restore state from snapshot
/// HensuState restored = snapshot.toState();
/// }
///
/// @param workflowId identifier of the workflow definition, not null
/// @param executionId unique identifier for this execution run, not null
/// @param currentNodeId the node where execution should resume, may be null if completed
/// @param context workflow variables and data, not null
/// @param history execution history including steps and backtracks, not null
/// @param activePlan current micro-plan state if planning is active, may be null
/// @param phase execution phase within the current node's lifecycle, not null after construction
/// @param createdAt when this snapshot was created, not null
/// @param checkpointReason why this checkpoint was created, may be null
/// @see HensuState for mutable execution state
/// @see Plan for active plan within a node
/// @see ExecutionPhase for phase semantics
public record HensuSnapshot(
        String workflowId,
        String executionId,
        String currentNodeId,
        Map<String, Object> context,
        ExecutionHistory history,
        Plan activePlan,
        ExecutionPhase phase,
        Instant createdAt,
        String checkpointReason)
        implements Serializable {

    /// Compact constructor with validation and defensive copying.
    ///
    /// **Implementation note – context wrapping:** uses
    /// {@code Collections.unmodifiableMap(new HashMap<>(…))} instead of
    /// {@code Map.copyOf()} because deserialized contexts may contain {@code null} values
    /// (Jackson maps JSON {@code null} to Java {@code null}), which {@code Map.copyOf()}
    /// rejects with {@link NullPointerException}.
    public HensuSnapshot {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        //noinspection Java9CollectionFactory
        context = context != null ? Collections.unmodifiableMap(new HashMap<>(context)) : Map.of();
        history = history != null ? history.copy() : new ExecutionHistory();
        phase = phase != null ? phase : ExecutionPhase.INITIAL;
        createdAt = createdAt != null ? createdAt : Instant.now();
    }

    /// Creates a snapshot from the current workflow state.
    ///
    /// @param state the current workflow state, not null
    /// @return new snapshot capturing current state, never null
    /// @throws NullPointerException if state is null
    public static HensuSnapshot from(HensuState state) {
        return from(state, null);
    }

    /// Creates a snapshot from the current workflow state with a reason.
    ///
    /// @param state the current workflow state, not null
    /// @param reason why this checkpoint is being created, may be null
    /// @return new snapshot capturing current state, never null
    /// @throws NullPointerException if state is null
    public static HensuSnapshot from(HensuState state, String reason) {
        Objects.requireNonNull(state, "state must not be null");

        return new HensuSnapshot(
                state.getWorkflowId(),
                state.getExecutionId(),
                state.getCurrentNode(),
                state.getContext(),
                state.getHistory(),
                state.getActivePlan(),
                state.getPhase(),
                Instant.now(),
                reason);
    }

    /// Restores workflow state from this snapshot.
    ///
    /// @return reconstructed workflow state, never null
    public HensuState toState() {
        return new HensuState.Builder()
                .workflowId(workflowId)
                .executionId(executionId)
                .currentNode(currentNodeId)
                .context(context)
                .history(history)
                .activePlan(activePlan)
                .phase(phase)
                .build();
    }

    /// Returns whether this snapshot has an active plan.
    ///
    /// @return true if a plan was in progress when snapshot was taken
    public boolean hasActivePlan() {
        return activePlan != null;
    }

    /// Returns whether the workflow was completed when this snapshot was taken.
    ///
    /// A snapshot is considered completed when either there is no current node
    /// to resume from, or the checkpoint reason explicitly indicates completion.
    ///
    /// @return true if the workflow has finished execution
    public boolean isCompleted() {
        return currentNodeId == null || "completed".equals(checkpointReason);
    }
}
