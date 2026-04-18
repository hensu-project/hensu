package io.hensu.server.workflow;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.validation.SubWorkflowGraphValidator;
import io.hensu.server.persistence.WorkflowPushLock;
import io.hensu.server.validation.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;

/// Service for workflow **definition** CRUD operations.
///
/// Owns all static workflow metadata concerns — push, pull, list, delete — separate from
/// runtime execution concerns (which live in {@link WorkflowExecutionService} and friends).
///
/// ### Push semantics
/// Push is idempotent create-or-update. Before saving, the service runs
/// {@link SubWorkflowGraphValidator} over the forward reachable graph from the incoming
/// workflow, lazily resolving referenced sub-workflow ids through the repository. A cycle
/// detected here fails the push before any persistence — the repository never holds a
/// cyclic state, even transiently.
///
/// ### Per-(tenant, workflow) atomicity
/// The validate→save critical section runs under {@link WorkflowPushLock}, which
/// serializes pushes for the same `(tenant, workflow)` cluster-wide via Postgres
/// advisory locks (or a JVM-local fallback in `inmem` mode). Two concurrent pushes
/// cannot each observe a clean graph and together introduce a cycle.
///
/// @see SubWorkflowGraphValidator
/// @see WorkflowPushLock
@ApplicationScoped
public class WorkflowRegistryService {

    private static final Logger LOG = Logger.getLogger(WorkflowRegistryService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowPushLock pushLock;

    @Inject
    public WorkflowRegistryService(
            WorkflowRepository workflowRepository, WorkflowPushLock pushLock) {
        this.workflowRepository =
                Objects.requireNonNull(workflowRepository, "workflowRepository must not be null");
        this.pushLock = Objects.requireNonNull(pushLock, "pushLock must not be null");
    }

    /// Pushes a workflow definition (create or update) after validating the sub-workflow
    /// reference graph is acyclic.
    ///
    /// @param tenantId the owning tenant, not null
    /// @param workflow the workflow definition to save, not null
    /// @return {@code true} if this was a create, {@code false} if an update of an existing id
    /// @throws IllegalStateException if pushing this workflow would introduce a cycle in the
    ///     sub-workflow reference graph
    public boolean pushWorkflow(String tenantId, Workflow workflow) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");

        return pushLock.withLock(
                tenantId,
                workflow.getId(),
                () -> {
                    // Validate reference existence and cycles BEFORE save — the repository never
                    // holds a broken or cyclic state. The resolver shadows the incoming workflow
                    // for its own id so the check sees the post-push graph without an intermediate
                    // write.
                    SubWorkflowGraphValidator.validate(
                            workflow,
                            id ->
                                    id.equals(workflow.getId())
                                            ? workflow
                                            : workflowRepository
                                                    .findById(tenantId, id)
                                                    .orElse(null));

                    boolean existed = workflowRepository.exists(tenantId, workflow.getId());
                    workflowRepository.save(tenantId, workflow);
                    return !existed;
                });
    }

    /// Looks up a workflow definition by id.
    ///
    /// @param tenantId the owning tenant, not null
    /// @param workflowId the workflow id, not null
    /// @return the workflow
    /// @throws WorkflowNotFoundException if no workflow with that id exists for the tenant
    public Workflow getWorkflow(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        return workflowRepository
                .findById(tenantId, workflowId)
                .orElseThrow(
                        () -> new WorkflowNotFoundException("Workflow not found: " + workflowId));
    }

    /// Lists every workflow definition for a tenant.
    ///
    /// @param tenantId the owning tenant, not null
    /// @return list of workflow definitions, never null, may be empty
    public List<Workflow> listWorkflows(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return workflowRepository.findAll(tenantId);
    }

    /// Deletes a workflow definition.
    ///
    /// @param tenantId the owning tenant, not null
    /// @param workflowId the workflow id to delete, not null
    /// @return {@code true} if a workflow was deleted, {@code false} if none matched
    public boolean deleteWorkflow(String tenantId, String workflowId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        LOG.infov(
                "Delete workflow: id={0}, tenant={1}",
                LogSanitizer.sanitize(workflowId), LogSanitizer.sanitize(tenantId));
        return workflowRepository.delete(tenantId, workflowId);
    }
}
