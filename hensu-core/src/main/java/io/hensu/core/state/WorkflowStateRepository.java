package io.hensu.core.state;

/// Writable repository for workflow execution state.
///
/// Provides checkpoint/restore operations for long-running workflows in a single-process
/// runtime, on top of the reads of {@link WorkflowStateReader}. Uses {@link HensuSnapshot}
/// for state representation.
///
/// A runtime whose writes must be guarded – the server writes only under a claim – does not
/// implement this interface; it implements {@link WorkflowStateReader} alone, so no caller
/// can reach a write that bypasses the guard.
///
/// ### Multi-Tenancy
/// All operations are tenant-scoped. The repository associates each
/// snapshot with a tenant ID for isolation.
///
/// ### Usage
/// {@snippet :
/// // Save checkpoint
/// HensuSnapshot snapshot = HensuSnapshot.from(state, "plan-review");
/// repository.save(tenantId, snapshot);
///
/// // Resume from checkpoint
/// Optional<HensuSnapshot> restored = repository.findByExecutionId(tenantId, executionId);
/// restored.ifPresent(s -> executor.resume(s.toState()));
/// }
///
/// @see WorkflowStateReader for the read operations
/// @see HensuSnapshot for state representation
/// @see InMemoryWorkflowStateRepository for in-memory implementation
public interface WorkflowStateRepository extends WorkflowStateReader {

    /// Saves a workflow state snapshot.
    ///
    /// If a snapshot with the same executionId exists, it will be updated.
    ///
    /// @param tenantId the tenant owning this execution, not null
    /// @param snapshot the state to persist, not null
    /// @throws NullPointerException if tenantId or snapshot is null
    void save(String tenantId, HensuSnapshot snapshot);

    /// Deletes a snapshot by execution ID.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution to delete, not null
    /// @return true if the snapshot was deleted, false if not found
    /// @throws NullPointerException if tenantId or executionId is null
    boolean delete(String tenantId, String executionId);

    /// Deletes all snapshots for a tenant.
    ///
    /// @param tenantId the tenant whose data to delete, not null
    /// @return number of snapshots deleted
    /// @throws NullPointerException if tenantId is null
    int deleteAllForTenant(String tenantId);
}
