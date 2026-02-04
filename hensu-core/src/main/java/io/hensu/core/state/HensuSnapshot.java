package io.hensu.core.state;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.plan.PlanSnapshot;
import java.io.Serializable;
import java.time.Instant;
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
/// @param createdAt when this snapshot was created, not null
/// @param checkpointReason why this checkpoint was created, may be null
/// @see HensuState for mutable execution state
/// @see PlanSnapshot for plan state within a node
public record HensuSnapshot(
        String workflowId,
        String executionId,
        String currentNodeId,
        Map<String, Object> context,
        ExecutionHistory history,
        PlanSnapshot activePlan,
        Instant createdAt,
        String checkpointReason)
        implements Serializable {

    /// Compact constructor with validation and defensive copying.
    public HensuSnapshot {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        context = context != null ? Map.copyOf(context) : Map.of();
        history = history != null ? history.copy() : new ExecutionHistory();
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
                null,
                Instant.now(),
                reason);
    }

    /// Creates a snapshot with an active plan state.
    ///
    /// @param state the current workflow state, not null
    /// @param planSnapshot the active plan execution state, not null
    /// @param reason why this checkpoint is being created, may be null
    /// @return new snapshot with plan state, never null
    public static HensuSnapshot from(HensuState state, PlanSnapshot planSnapshot, String reason) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(planSnapshot, "planSnapshot must not be null");

        return new HensuSnapshot(
                state.getWorkflowId(),
                state.getExecutionId(),
                state.getCurrentNode(),
                state.getContext(),
                state.getHistory(),
                planSnapshot,
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
    /// @return true if currentNodeId is null (no next node to execute)
    public boolean isCompleted() {
        return currentNodeId == null;
    }
}
