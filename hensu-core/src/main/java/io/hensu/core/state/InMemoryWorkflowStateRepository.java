package io.hensu.core.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory workflow state repository (default implementation).
///
/// Thread-safe, no external dependencies. Stores snapshots indexed by
/// tenant ID and execution ID.
///
/// ### Storage Structure
/// Uses nested maps: tenantId -> executionId -> snapshot
///
/// @see WorkflowStateRepository for contract
/// @see HensuSnapshot for state representation
public final class InMemoryWorkflowStateRepository implements WorkflowStateRepository {

    private final Map<String, Map<String, HensuSnapshot>> storage = new ConcurrentHashMap<>();

    @Override
    public void save(String tenantId, HensuSnapshot snapshot) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        storage.computeIfAbsent(tenantId, _ -> new ConcurrentHashMap<>())
                .put(snapshot.executionId(), snapshot);
    }

    @Override
    public Optional<HensuSnapshot> findByExecutionId(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        Map<String, HensuSnapshot> tenantSnapshots = storage.get(tenantId);
        if (tenantSnapshots == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantSnapshots.get(executionId));
    }

    /// Lists executions awaiting an out-of-band decision.
    ///
    /// Selects on the durable {@link ExecutionPhase}, not on the `checkpointReason` label, so
    /// that this repository answers the same question as the JDBC one. Resume looks an
    /// execution up by id and acts on its phase; a snapshot saved with any other reason while
    /// awaiting review would otherwise be resumable but invisible to operators.
    ///
    /// @param tenantId the tenant whose executions to list, not null
    /// @return snapshots awaiting a decision, never null
    @Override
    public List<HensuSnapshot> findPaused(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, HensuSnapshot> tenantSnapshots = storage.get(tenantId);
        if (tenantSnapshots == null) {
            return List.of();
        }

        return tenantSnapshots.values().stream()
                .filter(s -> s.currentNodeId() != null)
                .filter(s -> s.phase() instanceof ExecutionPhase.Awaiting)
                .toList();
    }

    @Override
    public List<HensuSnapshot> findByWorkflowId(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        Map<String, HensuSnapshot> tenantSnapshots = storage.get(tenantId);
        if (tenantSnapshots == null) {
            return List.of();
        }

        return tenantSnapshots.values().stream()
                .filter(s -> workflowId.equals(s.workflowId()))
                .toList();
    }

    @Override
    public boolean delete(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        Map<String, HensuSnapshot> tenantSnapshots = storage.get(tenantId);
        if (tenantSnapshots == null) {
            return false;
        }
        return tenantSnapshots.remove(executionId) != null;
    }

    @Override
    public int deleteAllForTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, HensuSnapshot> removed = storage.remove(tenantId);
        return removed != null ? removed.size() : 0;
    }

    /// Clears all data (useful for testing).
    public void clear() {
        storage.clear();
    }

    /// Returns count of snapshots for a tenant (useful for testing).
    public int countForTenant(String tenantId) {
        Map<String, HensuSnapshot> tenantSnapshots = storage.get(tenantId);
        return tenantSnapshots != null ? tenantSnapshots.size() : 0;
    }
}
