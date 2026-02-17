package io.hensu.core.workflow;

import java.util.List;
import java.util.Optional;

/// Repository for workflow definition persistence.
///
/// Stores compiled workflow definitions (from CLI push) for server execution.
/// Uses {@link Workflow} from hensu-core for workflow representation.
///
/// ### Multi-Tenancy
/// All operations are tenant-scoped. Each workflow is associated with a tenant ID.
///
/// ### Idempotent Operations
/// The {@link #save} method is idempotent - saving a workflow with an existing ID
/// will overwrite the previous definition.
///
/// ### Usage
/// {@snippet :
/// // Push workflow (create or update)
/// repository.save(tenantId, workflow);
///
/// // Pull workflow
/// Optional<Workflow> wf = repository.findById(tenantId, workflowId);
///
/// // List all workflows for tenant
/// List<Workflow> all = repository.findAll(tenantId);
///
/// // Delete workflow
/// repository.delete(tenantId, workflowId);
/// }
///
/// @see Workflow for workflow definition structure
/// @see InMemoryWorkflowRepository for in-memory implementation
public interface WorkflowRepository {

    /// Saves a workflow definition (idempotent).
    ///
    /// If a workflow with the same ID exists for the tenant, it will be overwritten.
    ///
    /// @param tenantId the tenant owning this workflow, not null
    /// @param workflow the workflow definition to persist, not null
    /// @throws NullPointerException if tenantId or workflow is null
    void save(String tenantId, Workflow workflow);

    /// Finds a workflow by ID.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param workflowId the workflow identifier, not null
    /// @return the workflow if found, empty otherwise
    /// @throws NullPointerException if tenantId or workflowId is null
    Optional<Workflow> findById(String tenantId, String workflowId);

    /// Lists all workflows for a tenant.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @return list of all workflows, never null (may be empty)
    /// @throws NullPointerException if tenantId is null
    List<Workflow> findAll(String tenantId);

    /// Checks if a workflow exists.
    ///
    /// @param tenantId the tenant to search within, not null
    /// @param workflowId the workflow identifier, not null
    /// @return true if the workflow exists, false otherwise
    /// @throws NullPointerException if tenantId or workflowId is null
    boolean exists(String tenantId, String workflowId);

    /// Deletes a workflow by ID.
    ///
    /// @param tenantId the tenant owning the workflow, not null
    /// @param workflowId the workflow to delete, not null
    /// @return true if the workflow was deleted, false if not found
    /// @throws NullPointerException if tenantId or workflowId is null
    boolean delete(String tenantId, String workflowId);

    /// Deletes all workflows for a tenant.
    ///
    /// @param tenantId the tenant whose workflows to delete, not null
    /// @return number of workflows deleted
    /// @throws NullPointerException if tenantId is null
    int deleteAllForTenant(String tenantId);

    /// Counts workflows for a tenant.
    ///
    /// @param tenantId the tenant to count workflows for, not null
    /// @return number of workflows
    /// @throws NullPointerException if tenantId is null
    int count(String tenantId);
}
