package io.hensu.core.state;

import java.util.List;
import java.util.Optional;

/// Read side of workflow execution state: looks executions up without changing them.
///
/// Split from {@link WorkflowStateRepository} so that a runtime whose writes follow a
/// stricter protocol can serve reads without also offering a write that bypasses it. The
/// server writes execution state only under a claim, so its store implements this interface
/// alone; a single-process runtime implements the full repository.
///
/// ### Multi-Tenancy
/// All operations are tenant-scoped: an execution is only ever found under the tenant that
/// owns it.
///
/// @implNote Implementations are thread-safe.
/// @see WorkflowStateRepository for the writable repository
/// @see HensuSnapshot for state representation
public interface WorkflowStateReader {

    /// Finds the latest snapshot of an execution.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param executionId the unique execution identifier, not null
    /// @return the snapshot if found, empty otherwise
    /// @throws NullPointerException if tenantId or executionId is null
    Optional<HensuSnapshot> findByExecutionId(String tenantId, String executionId);

    /// Finds the executions of a tenant that wait for a decision.
    ///
    /// Selects on the durable {@link ExecutionPhase} – an execution is listed when its phase
    /// is {@link ExecutionPhase.Awaiting} – not on the snapshot's `checkpointReason` label.
    /// A runtime that also tracks whether a run holds an execution lists only those no run
    /// holds, so every listed execution is one a resume accepts.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @return snapshots awaiting a decision, never null (may be empty)
    /// @throws NullPointerException if tenantId is null
    List<HensuSnapshot> findPaused(String tenantId);

    /// Finds the latest snapshot of every execution of a workflow.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param workflowId the workflow definition ID, not null
    /// @return one snapshot per execution, never null (may be empty)
    /// @throws NullPointerException if tenantId or workflowId is null
    List<HensuSnapshot> findByWorkflowId(String tenantId, String workflowId);
}
