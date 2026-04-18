package io.hensu.server.workflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Facade over the execution-side workflow services.
///
/// Provides a single injection point for `ExecutionResource` and other callers that want
/// the full execution API surface without depending on the internal split between
/// {@link WorkflowExecutionService} (dispatch), {@link ExecutionStateService} (resume), and
/// {@link ExecutionQueryService} (reads).
///
/// This class holds no state and contains no business logic — every method is a one-line
/// delegation. It exists solely to preserve a stable controller-facing API while keeping
/// the underlying services small and single-responsibility.
///
/// For workflow **definition** CRUD (push/pull/list/delete), use
/// {@link WorkflowRegistryService} directly — it has a different lifetime and concern
/// boundary and does not belong behind this facade.
@ApplicationScoped
public class WorkflowService {

    private final WorkflowExecutionService executionService;
    private final ExecutionStateService stateService;
    private final ExecutionQueryService queryService;

    @Inject
    public WorkflowService(
            WorkflowExecutionService executionService,
            ExecutionStateService stateService,
            ExecutionQueryService queryService) {
        this.executionService =
                Objects.requireNonNull(executionService, "executionService must not be null");
        this.stateService = Objects.requireNonNull(stateService, "stateService must not be null");
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
    }

    public ExecutionStartResult startExecution(
            String tenantId, String workflowId, Map<String, Object> context) {
        return executionService.startExecution(tenantId, workflowId, context);
    }

    public void resumeExecution(String tenantId, String executionId, ResumeDecision decision) {
        stateService.resumeExecution(tenantId, executionId, decision);
    }

    public Optional<PlanInfo> getPendingPlan(String tenantId, String executionId) {
        return queryService.getPendingPlan(tenantId, executionId);
    }

    public ExecutionStatus getExecutionStatus(String tenantId, String executionId) {
        return queryService.getExecutionStatus(tenantId, executionId);
    }

    public ExecutionOutput getExecutionResult(String tenantId, String executionId) {
        return queryService.getExecutionResult(tenantId, executionId);
    }

    public List<ExecutionSummary> listPausedExecutions(String tenantId) {
        return queryService.listPausedExecutions(tenantId);
    }
}
