package io.hensu.core.storage.workflow;

import io.hensu.core.state.HensuSnapshot;
import java.util.List;
import java.util.Optional;

/// Repository for workflow execution state persistence.
///
/// Provides checkpoint/restore operations for long-running workflows.
/// Uses {@link HensuSnapshot} from hensu-core for state representation.
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
/// @see HensuSnapshot for state representation
/// @see InMemoryWorkflowStateRepository for in-memory implementation
public interface WorkflowStateRepository {

    /// Saves a workflow state snapshot.
    ///
    /// If a snapshot with the same executionId exists, it will be updated.
    ///
    /// @param tenantId the tenant owning this execution, not null
    /// @param snapshot the state to persist, not null
    /// @throws NullPointerException if tenantId or snapshot is null
    void save(String tenantId, HensuSnapshot snapshot);

    /// Finds a snapshot by execution ID.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param executionId the unique execution identifier, not null
    /// @return the snapshot if found, empty otherwise
    /// @throws NullPointerException if tenantId or executionId is null
    Optional<HensuSnapshot> findByExecutionId(String tenantId, String executionId);

    /// Finds all paused executions for a tenant.
    ///
    /// Returns executions where currentNodeId is not null (not completed).
    ///
    /// @param tenantId the tenant to search within, not null
    /// @return list of paused snapshots, never null (may be empty)
    /// @throws NullPointerException if tenantId is null
    List<HensuSnapshot> findPaused(String tenantId);

    /// Finds all executions for a workflow.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param workflowId the workflow definition ID, not null
    /// @return list of snapshots for the workflow, never null
    /// @throws NullPointerException if tenantId or workflowId is null
    List<HensuSnapshot> findByWorkflowId(String tenantId, String workflowId);

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
