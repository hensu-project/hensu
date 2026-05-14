package io.hensu.core.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory workflow repository (default implementation).
///
/// Thread-safe, no external dependencies. Stores workflows indexed by
/// tenant ID and workflow ID.
///
/// ### Storage Structure
/// Uses nested maps: tenantId -> workflowId -> workflow
///
/// ### Idempotent Save
/// Saving a workflow with an existing ID overwrites the previous definition.
///
/// @implNote Uses ConcurrentHashMap for thread-safety.
/// @see WorkflowRepository for contract
/// @see Workflow for workflow structure
public final class InMemoryWorkflowRepository implements WorkflowRepository {

    private final Map<String, Map<String, Workflow>> storage = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> deleted = new ConcurrentHashMap<>();

    @Override
    public void save(String tenantId, Workflow workflow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");

        storage.computeIfAbsent(tenantId, _ -> new ConcurrentHashMap<>())
                .put(workflow.getId(), workflow);
        // Re-push reactivates a soft-deleted workflow
        Set<String> deletedIds = deleted.get(tenantId);
        if (deletedIds != null) {
            deletedIds.remove(workflow.getId());
        }
    }

    @Override
    public Optional<Workflow> findById(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        if (isDeleted(tenantId, workflowId)) {
            return Optional.empty();
        }
        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantWorkflows.get(workflowId));
    }

    @Override
    public List<Workflow> findAll(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null) {
            return List.of();
        }
        Set<String> deletedIds = deleted.getOrDefault(tenantId, Set.of());
        return tenantWorkflows.values().stream()
                .filter(wf -> !deletedIds.contains(wf.getId()))
                .toList();
    }

    @Override
    public boolean exists(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        if (isDeleted(tenantId, workflowId)) {
            return false;
        }
        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        return tenantWorkflows != null && tenantWorkflows.containsKey(workflowId);
    }

    @Override
    public boolean delete(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        if (isDeleted(tenantId, workflowId)) {
            return false;
        }
        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null || !tenantWorkflows.containsKey(workflowId)) {
            return false;
        }
        deleted.computeIfAbsent(tenantId, _ -> ConcurrentHashMap.newKeySet()).add(workflowId);
        return true;
    }

    @Override
    public int deleteAllForTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null) {
            return 0;
        }
        Set<String> deletedIds = deleted.getOrDefault(tenantId, Set.of());
        int activeCount =
                (int)
                        tenantWorkflows.keySet().stream()
                                .filter(id -> !deletedIds.contains(id))
                                .count();
        if (activeCount > 0) {
            deleted.computeIfAbsent(tenantId, _ -> ConcurrentHashMap.newKeySet())
                    .addAll(tenantWorkflows.keySet());
        }
        return activeCount;
    }

    @Override
    public int count(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null) {
            return 0;
        }
        Set<String> deletedIds = deleted.getOrDefault(tenantId, Set.of());
        return (int)
                tenantWorkflows.keySet().stream().filter(id -> !deletedIds.contains(id)).count();
    }

    /// Clears all data (useful for testing).
    public void clear() {
        storage.clear();
        deleted.clear();
    }

    private boolean isDeleted(String tenantId, String workflowId) {
        Set<String> deletedIds = deleted.get(tenantId);
        return deletedIds != null && deletedIds.contains(workflowId);
    }
}
