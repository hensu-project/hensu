package io.hensu.server.api;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/// REST API for workflow definition management.
///
/// Provides terraform/kubectl-style endpoints for:
/// - Pushing workflow definitions (create or update)
/// - Pulling workflow definitions
/// - Listing workflows for a tenant
/// - Deleting workflows
///
/// All endpoints require `X-Tenant-ID` header for multi-tenant isolation.
///
/// ### Usage Pattern
/// CLI compiles Kotlin DSL to JSON and pushes to server:
/// ```
/// hensu push workflow.kt  → POST /api/v1/workflows
/// hensu pull <id>         → GET /api/v1/workflows/{id}
/// hensu list              → GET /api/v1/workflows
/// hensu delete <id>       → DELETE /api/v1/workflows/{id}
/// ```
///
/// @see WorkflowRepository for persistence
/// @see ExecutionResource for execution operations
@Path("/api/v1/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    private static final Logger LOG = Logger.getLogger(WorkflowResource.class);

    private final WorkflowRepository workflowRepository;

    @Inject
    public WorkflowResource(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /// Pushes a workflow definition (idempotent create or update).
    ///
    /// ### Request
    /// ```
    /// POST /api/v1/workflows
    /// X-Tenant-ID: tenant-123
    /// Content-Type: application/json
    ///
    /// {
    ///   "id": "order-processing",
    ///   "version": "1.0.0",
    ///   "startNode": "start",
    ///   "nodes": {...},
    ///   "agents": {...},
    ///   "rubrics": {...}
    /// }
    /// ```
    ///
    /// ### Response (200 OK for update, 201 Created for new)
    /// ```json
    /// {"id": "order-processing", "version": "1.0.0", "created": true}
    /// ```
    @POST
    public Response pushWorkflow(@HeaderParam("X-Tenant-ID") String tenantId, Workflow workflow) {

        validateTenantId(tenantId);

        if (workflow == null) {
            throw new BadRequestException("Workflow definition is required");
        }

        LOG.infov(
                "Push workflow: id={0}, version={1}, tenant={2}",
                workflow.getId(), workflow.getVersion(), tenantId);

        boolean exists = workflowRepository.exists(tenantId, workflow.getId());
        workflowRepository.save(tenantId, workflow);

        Response.Status status = exists ? Response.Status.OK : Response.Status.CREATED;

        return Response.status(status)
                .entity(
                        Map.of(
                                "id", workflow.getId(),
                                "version", workflow.getVersion(),
                                "created", !exists))
                .build();
    }

    /// Pulls a workflow definition by ID.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/workflows/{workflowId}
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {
    ///   "id": "order-processing",
    ///   "version": "1.0.0",
    ///   "startNode": "start",
    ///   "nodes": {...},
    ///   "agents": {...},
    ///   "rubrics": {...}
    /// }
    /// ```
    @GET
    @Path("/{workflowId}")
    public Response pullWorkflow(
            @PathParam("workflowId") String workflowId,
            @HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        LOG.debugv("Pull workflow: id={0}, tenant={1}", workflowId, tenantId);

        Workflow workflow =
                workflowRepository
                        .findById(tenantId, workflowId)
                        .orElseThrow(
                                () -> new NotFoundException("Workflow not found: " + workflowId));

        return Response.ok().entity(workflow).build();
    }

    /// Lists all workflows for a tenant.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/workflows
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// [
    ///   {"id": "order-processing", "version": "1.0.0"},
    ///   {"id": "user-onboarding", "version": "2.1.0"}
    /// ]
    /// ```
    @GET
    public Response listWorkflows(@HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        LOG.debugv("List workflows: tenant={0}", tenantId);

        List<Workflow> workflows = workflowRepository.findAll(tenantId);

        List<Map<String, String>> summaries =
                workflows.stream()
                        .map(w -> Map.of("id", w.getId(), "version", w.getVersion()))
                        .toList();

        return Response.ok().entity(summaries).build();
    }

    /// Deletes a workflow definition.
    ///
    /// ### Request
    /// ```
    /// DELETE /api/v1/workflows/{workflowId}
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (204 No Content)
    @DELETE
    @Path("/{workflowId}")
    public Response deleteWorkflow(
            @PathParam("workflowId") String workflowId,
            @HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        LOG.infov("Delete workflow: id={0}, tenant={1}", workflowId, tenantId);

        boolean deleted = workflowRepository.delete(tenantId, workflowId);

        if (!deleted) {
            throw new NotFoundException("Workflow not found: " + workflowId);
        }

        return Response.noContent().build();
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("X-Tenant-ID header is required");
        }
    }
}
