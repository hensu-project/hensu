package io.hensu.server.workflow;

import io.hensu.core.plan.PlannedStep;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Read-only service for querying execution state.
///
/// Fetches status, pending plan info, public output, and paused execution summaries from
/// the {@link WorkflowStateRepository}. Does not mutate any state — any change to an
/// execution goes through {@link WorkflowExecutionService} or {@link ExecutionStateService}.
@ApplicationScoped
public class ExecutionQueryService {

    private final WorkflowStateRepository stateRepository;

    @Inject
    public ExecutionQueryService(WorkflowStateRepository stateRepository) {
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
    }

    /// Gets the current plan for an execution awaiting review.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the plan if one is pending review, empty if no plan is active
    /// @throws ExecutionNotFoundException if execution not found
    public Optional<PlanInfo> getPendingPlan(String tenantId, String executionId) {
        HensuSnapshot snapshot = loadSnapshot(tenantId, executionId);
        if (!snapshot.hasActivePlan()) {
            return Optional.empty();
        }
        var plan = snapshot.activePlan();
        int currentStep =
                plan.steps().stream()
                        .filter(s -> !s.isFinished())
                        .mapToInt(PlannedStep::index)
                        .findFirst()
                        .orElse(plan.stepCount());
        return Optional.of(new PlanInfo(plan.id(), plan.stepCount(), currentStep));
    }

    /// Gets execution status by ID.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the execution status, never null
    /// @throws ExecutionNotFoundException if execution not found
    public ExecutionStatus getExecutionStatus(String tenantId, String executionId) {
        HensuSnapshot snapshot = loadSnapshot(tenantId, executionId);
        String status = snapshot.isCompleted() ? "COMPLETED" : "PAUSED";
        return new ExecutionStatus(
                executionId,
                snapshot.workflowId(),
                status,
                snapshot.currentNodeId(),
                snapshot.hasActivePlan(),
                extractCorrelationId(snapshot));
    }

    /// Gets the public output of a completed or paused execution.
    ///
    /// Returns the workflow context at the time of the last checkpoint with internal
    /// system keys (prefixed with `_`) filtered out.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the execution output, never null
    /// @throws ExecutionNotFoundException if execution not found
    public ExecutionOutput getExecutionResult(String tenantId, String executionId) {
        HensuSnapshot snapshot = loadSnapshot(tenantId, executionId);
        String status = snapshot.isCompleted() ? "COMPLETED" : "PAUSED";
        return new ExecutionOutput(
                executionId,
                snapshot.workflowId(),
                status,
                WorkflowContextUtil.publicContext(snapshot.context()));
    }

    /// Lists all paused executions for a tenant.
    ///
    /// @param tenantId the tenant ID, not null
    /// @return list of paused execution summaries, never null, may be empty
    public List<ExecutionSummary> listPausedExecutions(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return stateRepository.findPaused(tenantId).stream()
                .map(
                        s ->
                                new ExecutionSummary(
                                        s.executionId(),
                                        s.workflowId(),
                                        s.currentNodeId(),
                                        s.createdAt(),
                                        extractCorrelationId(s)))
                .toList();
    }

    private static String extractCorrelationId(HensuSnapshot snapshot) {
        return snapshot.phase() instanceof ExecutionPhase.Awaiting awaiting
                ? awaiting.correlationId()
                : null;
    }

    private HensuSnapshot loadSnapshot(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        return stateRepository
                .findByExecutionId(tenantId, executionId)
                .orElseThrow(
                        () ->
                                new ExecutionNotFoundException(
                                        "Execution not found: " + executionId));
    }
}
