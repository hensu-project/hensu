package io.hensu.core.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    @Override
    public void save(String tenantId, Workflow workflow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");

        storage.computeIfAbsent(tenantId, _ -> new ConcurrentHashMap<>())
                .put(workflow.getId(), workflow);
    }

    @Override
    public Optional<Workflow> findById(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

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
        return List.copyOf(tenantWorkflows.values());
    }

    @Override
    public boolean exists(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        return tenantWorkflows != null && tenantWorkflows.containsKey(workflowId);
    }

    @Override
    public boolean delete(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        if (tenantWorkflows == null) {
            return false;
        }
        return tenantWorkflows.remove(workflowId) != null;
    }

    @Override
    public int deleteAllForTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, Workflow> removed = storage.remove(tenantId);
        return removed != null ? removed.size() : 0;
    }

    @Override
    public int count(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        Map<String, Workflow> tenantWorkflows = storage.get(tenantId);
        return tenantWorkflows != null ? tenantWorkflows.size() : 0;
    }

    /// Clears all data (useful for testing).
    public void clear() {
        storage.clear();
    }
}
