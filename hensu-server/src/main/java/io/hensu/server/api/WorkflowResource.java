package io.hensu.server.api;

import io.hensu.server.service.WorkflowService;
import io.hensu.server.service.WorkflowService.ExecutionNotFoundException;
import io.hensu.server.service.WorkflowService.ExecutionStartResult;
import io.hensu.server.service.WorkflowService.ExecutionStatus;
import io.hensu.server.service.WorkflowService.ExecutionSummary;
import io.hensu.server.service.WorkflowService.PlanInfo;
import io.hensu.server.service.WorkflowService.ResumeDecision;
import io.hensu.server.service.WorkflowService.WorkflowNotFoundException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/// REST API for workflow operations.
///
/// Provides endpoints for:
/// - Starting workflow executions
/// - Resuming paused executions
/// - Querying execution status and plans
///
/// All endpoints require `X-Tenant-ID` header for multi-tenant isolation.
///
/// @see WorkflowService for business logic
@Path("/api/v1/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    private static final Logger LOG = Logger.getLogger(WorkflowResource.class);

    private final WorkflowService workflowService;

    @Inject
    public WorkflowResource(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /// Starts a new workflow execution.
    ///
    /// ### Request
    /// ```
    /// POST /api/v1/workflows/{workflowId}/execute
    /// X-Tenant-ID: tenant-123
    /// Content-Type: application/json
    ///
    /// {"orderId": "123", "userId": "456"}
    /// ```
    ///
    /// ### Response (202 Accepted)
    /// ```json
    /// {"executionId": "exec-abc", "workflowId": "order-processing"}
    /// ```
    @POST
    @Path("/{workflowId}/execute")
    public Response execute(
            @PathParam("workflowId") String workflowId,
            @HeaderParam("X-Tenant-ID") String tenantId,
            Map<String, Object> context) {

        validateTenantId(tenantId);

        LOG.infov("Execute workflow request: workflow={0}, tenant={1}", workflowId, tenantId);

        try {
            ExecutionStartResult result =
                    workflowService.startExecution(
                            tenantId, workflowId, context != null ? context : Map.of());

            return Response.accepted()
                    .entity(
                            Map.of(
                                    "executionId", result.executionId(),
                                    "workflowId", result.workflowId()))
                    .build();
        } catch (WorkflowNotFoundException e) {
            LOG.warnv("Workflow not found: {0}", workflowId);
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Resumes a paused workflow execution.
    ///
    /// ### Request
    /// ```
    /// POST /api/v1/workflows/executions/{executionId}/resume
    /// X-Tenant-ID: tenant-123
    /// Content-Type: application/json
    ///
    /// {"approved": true, "modifications": {}}
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {"status": "resumed"}
    /// ```
    @POST
    @Path("/executions/{executionId}/resume")
    public Response resume(
            @PathParam("executionId") String executionId,
            @HeaderParam("X-Tenant-ID") String tenantId,
            ResumeRequest request) {

        validateTenantId(tenantId);

        LOG.infov("Resume execution request: executionId={0}, tenant={1}", executionId, tenantId);

        try {
            ResumeDecision decision =
                    request != null && request.approved()
                            ? ResumeDecision.modify(
                                    request.modifications() != null
                                            ? request.modifications()
                                            : Map.of())
                            : ResumeDecision.approve();

            workflowService.resumeExecution(tenantId, executionId, decision);

            return Response.ok().entity(Map.of("status", "resumed")).build();
        } catch (ExecutionNotFoundException e) {
            LOG.warnv("Execution not found: {0}", executionId);
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Gets the pending plan for an execution awaiting review.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/workflows/executions/{executionId}/plan
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {"planId": "plan-123", "totalSteps": 5, "currentStep": 2}
    /// ```
    @GET
    @Path("/executions/{executionId}/plan")
    public Response getPlan(
            @PathParam("executionId") String executionId,
            @HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        try {
            PlanInfo planInfo =
                    workflowService
                            .getPendingPlan(tenantId, executionId)
                            .orElseThrow(
                                    () ->
                                            new NotFoundException(
                                                    "No pending plan for execution: "
                                                            + executionId));

            return Response.ok()
                    .entity(
                            Map.of(
                                    "planId", planInfo.planId(),
                                    "totalSteps", planInfo.totalSteps(),
                                    "currentStep", planInfo.currentStep()))
                    .build();
        } catch (ExecutionNotFoundException e) {
            LOG.warnv("Execution not found: {0}", executionId);
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Gets the status of an execution.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/workflows/executions/{executionId}
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {
    ///   "executionId": "exec-123",
    ///   "workflowId": "order-processing",
    ///   "status": "PAUSED",
    ///   "currentNodeId": "validate-payment",
    ///   "hasPendingPlan": true
    /// }
    /// ```
    @GET
    @Path("/executions/{executionId}")
    public Response getExecution(
            @PathParam("executionId") String executionId,
            @HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        try {
            ExecutionStatus status = workflowService.getExecutionStatus(tenantId, executionId);

            return Response.ok()
                    .entity(
                            Map.of(
                                    "executionId", status.executionId(),
                                    "workflowId", status.workflowId(),
                                    "status", status.status(),
                                    "currentNodeId",
                                            status.currentNodeId() != null
                                                    ? status.currentNodeId()
                                                    : "",
                                    "hasPendingPlan", status.hasPendingPlan()))
                    .build();
        } catch (ExecutionNotFoundException e) {
            LOG.warnv("Execution not found: {0}", executionId);
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Lists paused executions for the tenant.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/workflows/executions/paused
    /// X-Tenant-ID: tenant-123
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// [
    ///   {"executionId": "exec-1", "workflowId": "wf-1", "currentNodeId": "node-1"},
    ///   {"executionId": "exec-2", "workflowId": "wf-2", "currentNodeId": "node-3"}
    /// ]
    /// ```
    @GET
    @Path("/executions/paused")
    public Response listPausedExecutions(@HeaderParam("X-Tenant-ID") String tenantId) {

        validateTenantId(tenantId);

        List<ExecutionSummary> paused = workflowService.listPausedExecutions(tenantId);

        List<Map<String, Object>> response =
                paused.stream()
                        .map(
                                s ->
                                        Map.<String, Object>of(
                                                "executionId", s.executionId(),
                                                "workflowId", s.workflowId(),
                                                "currentNodeId",
                                                        s.currentNodeId() != null
                                                                ? s.currentNodeId()
                                                                : "",
                                                "createdAt", s.createdAt().toString()))
                        .toList();

        return Response.ok().entity(response).build();
    }

    /// Validates tenant ID header is present.
    private void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("X-Tenant-ID header is required");
        }
    }

    /// Request body for resume endpoint.
    public record ResumeRequest(boolean approved, Map<String, Object> modifications) {}
}
