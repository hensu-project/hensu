package io.hensu.server.api;

import io.hensu.server.security.RequestTenantResolver;
import io.hensu.server.service.WorkflowService;
import io.hensu.server.service.WorkflowService.*;
import io.hensu.server.validation.LogSanitizer;
import io.hensu.server.validation.ValidId;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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

/// REST API for workflow execution operations.
///
/// Provides endpoints for:
/// - Starting workflow executions
/// - Resuming paused executions
/// - Querying execution status, plans, and final output
///
/// Tenant identity is resolved from the JWT `tenant_id` claim via
/// {@link RequestTenantResolver}. In dev/test mode, a default tenant is used.
///
/// @see WorkflowService for business logic
/// @see WorkflowResource for workflow definition management
@Path("/api/v1/executions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExecutionResource {

    private static final Logger LOG = Logger.getLogger(ExecutionResource.class);

    private final WorkflowService workflowService;
    private final RequestTenantResolver tenantResolver;

    @Inject
    public ExecutionResource(
            WorkflowService workflowService, RequestTenantResolver tenantResolver) {
        this.workflowService = workflowService;
        this.tenantResolver = tenantResolver;
    }

    /// Starts a new workflow execution.
    ///
    /// ### Request
    /// ```
    /// POST /api/v1/executions
    /// Authorization: Bearer <jwt>
    /// Content-Type: application/json
    ///
    /// {"workflowId": "order-processing", "context": {"orderId": "123"}}
    /// ```
    ///
    /// ### Response (202 Accepted)
    /// ```json
    /// {"executionId": "exec-abc", "workflowId": "order-processing"}
    /// ```
    @POST
    public Response startExecution(@Valid @NotNull ExecutionStartRequest request) {

        String tenantId = tenantResolver.tenantId();

        LOG.infov(
                "Start execution request: workflow={0}, tenant={1}",
                request.workflowId(), tenantId);

        try {
            ExecutionStartResult result =
                    workflowService.startExecution(
                            tenantId,
                            request.workflowId(),
                            request.context() != null ? request.context() : Map.of());

            return Response.accepted()
                    .entity(
                            Map.of(
                                    "executionId", result.executionId(),
                                    "workflowId", result.workflowId()))
                    .build();
        } catch (WorkflowNotFoundException e) {
            LOG.warnv("Workflow not found: {0}", request.workflowId());
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Gets the status of an execution.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/executions/{executionId}
    /// Authorization: Bearer <jwt>
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
    @Path("/{executionId}")
    public Response getExecution(@PathParam("executionId") @ValidId String executionId) {

        String tenantId = tenantResolver.tenantId();

        try {
            ExecutionStatus status = workflowService.getExecutionStatus(tenantId, executionId);

            return Response.ok()
                    .entity(
                            Map.of(
                                    "executionId",
                                    status.executionId(),
                                    "workflowId",
                                    status.workflowId(),
                                    "status",
                                    status.status(),
                                    "currentNodeId",
                                    status.currentNodeId() != null ? status.currentNodeId() : "",
                                    "hasPendingPlan",
                                    status.hasPendingPlan()))
                    .build();
        } catch (ExecutionNotFoundException e) {
            LOG.warnv("Execution not found: {0}", LogSanitizer.sanitize(executionId));
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Resumes a paused workflow execution.
    ///
    /// ### Request
    /// ```
    /// POST /api/v1/executions/{executionId}/resume
    /// Authorization: Bearer <jwt>
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
    @Path("/{executionId}/resume")
    public Response resume(
            @PathParam("executionId") @ValidId String executionId, @Valid ResumeRequest request) {

        String tenantId = tenantResolver.tenantId();

        LOG.infov(
                "Resume execution request: executionId={0}, tenant={1}",
                LogSanitizer.sanitize(executionId), tenantId);

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
            LOG.warnv("Execution not found: {0}", LogSanitizer.sanitize(executionId));
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Gets the pending plan for an execution awaiting review.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/executions/{executionId}/plan
    /// Authorization: Bearer <jwt>
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {"planId": "plan-123", "totalSteps": 5, "currentStep": 2}
    /// ```
    @GET
    @Path("/{executionId}/plan")
    public Response getPlan(@PathParam("executionId") @ValidId String executionId) {

        String tenantId = tenantResolver.tenantId();

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
            LOG.warnv("Execution not found: {0}", LogSanitizer.sanitize(executionId));
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Lists paused executions for the tenant.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/executions/paused
    /// Authorization: Bearer <jwt>
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
    @Path("/paused")
    public Response listPausedExecutions() {

        String tenantId = tenantResolver.tenantId();

        List<ExecutionSummary> paused = workflowService.listPausedExecutions(tenantId);

        List<Map<String, Object>> response =
                paused.stream()
                        .map(
                                s ->
                                        Map.<String, Object>of(
                                                "executionId",
                                                s.executionId(),
                                                "workflowId",
                                                s.workflowId(),
                                                "currentNodeId",
                                                s.currentNodeId() != null ? s.currentNodeId() : "",
                                                "createdAt",
                                                s.createdAt().toString()))
                        .toList();

        return Response.ok().entity(response).build();
    }

    /// Gets the final output of a completed or paused execution.
    ///
    /// Returns the workflow context at the last checkpoint with internal system
    /// keys (prefixed with `_`) excluded. Use this endpoint to retrieve the
    /// workflow result when not consuming the SSE stream, or as a fallback
    /// after receiving an `execution.completed` event.
    ///
    /// ### Request
    /// ```
    /// GET /api/v1/executions/{executionId}/result
    /// Authorization: Bearer <jwt>
    /// ```
    ///
    /// ### Response (200 OK)
    /// ```json
    /// {
    ///   "executionId": "exec-123",
    ///   "workflowId": "order-processing",
    ///   "status": "COMPLETED",
    ///   "output": {"summary": "Order validated", "approved": true}
    /// }
    /// ```
    ///
    /// @param executionId the execution identifier, not null
    /// @return 200 with output, or 404 if not found
    @GET
    @Path("/{executionId}/result")
    public Response getExecutionResult(@PathParam("executionId") @ValidId String executionId) {

        String tenantId = tenantResolver.tenantId();

        try {
            ExecutionOutput output = workflowService.getExecutionResult(tenantId, executionId);

            return Response.ok()
                    .entity(
                            Map.of(
                                    "executionId", output.executionId(),
                                    "workflowId", output.workflowId(),
                                    "status", output.status(),
                                    "output", output.output()))
                    .build();
        } catch (ExecutionNotFoundException e) {
            LOG.warnv("Execution not found: {0}", LogSanitizer.sanitize(executionId));
            throw new NotFoundException(e.getMessage());
        }
    }

    /// Request body for starting an execution.
    ///
    /// @param workflowId the workflow to execute, not null, not blank
    /// @param context initial execution context variables, may be null
    public record ExecutionStartRequest(
            @NotBlank(message = "workflowId is required") @ValidId String workflowId,
            Map<String, Object> context) {}

    /// Request body for resuming a paused execution.
    ///
    /// @param approved whether the pending plan is approved
    /// @param modifications optional context modifications, may be null
    public record ResumeRequest(boolean approved, Map<String, Object> modifications) {}
}
