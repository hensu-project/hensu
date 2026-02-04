package io.hensu.server.service;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.storage.workflow.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serial;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/// Service for workflow execution and state management.
///
/// Handles business logic for workflow operations:
/// - Starting new workflow executions
/// - Resuming paused executions
/// - Retrieving execution state and plans
///
/// All operations are tenant-scoped via {@link TenantContext}.
///
/// @see io.hensu.server.api.WorkflowResource for REST API endpoints
/// @see WorkflowExecutor for core execution logic
@ApplicationScoped
public class WorkflowService {

    private static final Logger LOG = Logger.getLogger(WorkflowService.class);

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowStateRepository stateRepository;
    private final ExecutionEventBroadcaster eventBroadcaster;

    @Inject
    public WorkflowService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            ExecutionEventBroadcaster eventBroadcaster) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
    }

    /// Starts a new workflow execution.
    ///
    /// @param tenantId the tenant requesting execution, not null
    /// @param workflowId the workflow to execute, not null
    /// @param context initial context variables, not null
    /// @return execution result containing execution ID
    /// @throws WorkflowNotFoundException if workflow not found
    /// @throws WorkflowExecutionException if execution fails
    public ExecutionStartResult startExecution(
            String tenantId, String workflowId, Map<String, Object> context) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        LOG.infov("Starting workflow execution: workflow={0}, tenant={1}", workflowId, tenantId);

        String executionId = UUID.randomUUID().toString();

        TenantInfo tenant = TenantInfo.simple(tenantId);

        // Publish execution started event
        eventBroadcaster.publish(
                executionId,
                ExecutionEvent.ExecutionStarted.now(executionId, workflowId, tenantId));

        // Set execution context for PlanObserver events
        eventBroadcaster.setCurrentExecution(executionId);

        try {
            TenantContext.runAs(
                    tenant,
                    () -> {
                        Workflow workflow = loadWorkflow(workflowId);
                        ExecutionResult result = workflowExecutor.execute(workflow, context);

                        // Save final state and publish completion event
                        if (result instanceof ExecutionResult.Completed completed) {
                            HensuSnapshot snapshot =
                                    HensuSnapshot.from(completed.finalState(), "completed");
                            stateRepository.save(tenantId, snapshot);
                            eventBroadcaster.publish(
                                    executionId,
                                    ExecutionEvent.ExecutionCompleted.success(
                                            executionId,
                                            workflowId,
                                            completed.finalState().getCurrentNode()));
                        } else if (result instanceof ExecutionResult.Rejected rejected) {
                            HensuSnapshot snapshot =
                                    HensuSnapshot.from(rejected.state(), "rejected");
                            stateRepository.save(tenantId, snapshot);
                            eventBroadcaster.publish(
                                    executionId,
                                    ExecutionEvent.ExecutionCompleted.failure(
                                            executionId,
                                            workflowId,
                                            rejected.state().getCurrentNode()));
                        } else if (result
                                instanceof
                                ExecutionResult.Failure(
                                        HensuState currentState,
                                        IllegalStateException e)) {
                            HensuSnapshot snapshot = HensuSnapshot.from(currentState, "failed");
                            stateRepository.save(tenantId, snapshot);
                            eventBroadcaster.publish(
                                    executionId,
                                    ExecutionEvent.ExecutionError.now(
                                            executionId,
                                            "ExecutionFailure",
                                            e.getMessage(),
                                            currentState.getCurrentNode()));
                        }
                        return null;
                    });
        } catch (Exception e) {
            LOG.errorv(
                    e, "Workflow execution failed: workflow={0}, tenant={1}", workflowId, tenantId);
            eventBroadcaster.publish(
                    executionId,
                    ExecutionEvent.ExecutionError.now(
                            executionId, e.getClass().getSimpleName(), e.getMessage(), null));
            throw new WorkflowExecutionException("Workflow execution failed: " + e.getMessage(), e);
        } finally {
            // Clear execution context and complete the stream
            eventBroadcaster.setCurrentExecution(null);
            eventBroadcaster.complete(executionId);
        }

        LOG.infov("Workflow execution completed: executionId={0}", executionId);
        return new ExecutionStartResult(executionId, workflowId);
    }

    /// Resumes a paused workflow execution.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution to resume, not null
    /// @param decision the resume decision (approve/modify plan), may be null
    /// @throws ExecutionNotFoundException if execution not found
    /// @throws WorkflowExecutionException if resume fails
    public void resumeExecution(String tenantId, String executionId, ResumeDecision decision) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        LOG.infov(
                "Resuming workflow execution: executionId={0}, tenant={1}", executionId, tenantId);

        HensuSnapshot snapshot =
                stateRepository
                        .findByExecutionId(tenantId, executionId)
                        .orElseThrow(
                                () ->
                                        new ExecutionNotFoundException(
                                                "Execution not found: " + executionId));

        TenantInfo tenant = TenantInfo.simple(tenantId);

        try {
            TenantContext.runAs(
                    tenant,
                    () -> {
                        HensuState state = snapshot.toState();

                        // TODO: Apply decision and resume execution
                        // This requires workflow to be loaded and executor to support resume
                        LOG.infov(
                                "Restored state for execution: {0}, currentNode={1}",
                                executionId, state.getCurrentNode());
                    });
        } catch (Exception e) {
            LOG.errorv(e, "Resume execution failed: executionId={0}", executionId);
            throw new WorkflowExecutionException("Resume failed: " + e.getMessage(), e);
        }
    }

    /// Gets the current plan for an execution awaiting review.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the plan if one is pending review
    /// @throws ExecutionNotFoundException if execution not found
    public Optional<PlanInfo> getPendingPlan(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        HensuSnapshot snapshot =
                stateRepository
                        .findByExecutionId(tenantId, executionId)
                        .orElseThrow(
                                () ->
                                        new ExecutionNotFoundException(
                                                "Execution not found: " + executionId));

        if (!snapshot.hasActivePlan()) {
            return Optional.empty();
        }

        return Optional.of(
                new PlanInfo(
                        snapshot.activePlan().nodeId(),
                        snapshot.activePlan().steps().size(),
                        snapshot.activePlan().currentStepIndex()));
    }

    /// Gets execution status by ID.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the execution status
    /// @throws ExecutionNotFoundException if execution not found
    public ExecutionStatus getExecutionStatus(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        HensuSnapshot snapshot =
                stateRepository
                        .findByExecutionId(tenantId, executionId)
                        .orElseThrow(
                                () ->
                                        new ExecutionNotFoundException(
                                                "Execution not found: " + executionId));

        String status = snapshot.isCompleted() ? "COMPLETED" : "PAUSED";
        return new ExecutionStatus(
                executionId,
                snapshot.workflowId(),
                status,
                snapshot.currentNodeId(),
                snapshot.hasActivePlan());
    }

    /// Lists all paused executions for a tenant.
    ///
    /// @param tenantId the tenant ID, not null
    /// @return list of paused execution summaries
    public List<ExecutionSummary> listPausedExecutions(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        return stateRepository.findPaused(tenantId).stream()
                .map(
                        s ->
                                new ExecutionSummary(
                                        s.executionId(),
                                        s.workflowId(),
                                        s.currentNodeId(),
                                        s.createdAt()))
                .toList();
    }

    /// Loads a workflow by ID.
    ///
    /// @param workflowId the workflow ID to load
    /// @return the workflow
    /// @throws WorkflowNotFoundException if not found
    private Workflow loadWorkflow(String workflowId) {
        // TODO: Load from workflow registry
        throw new WorkflowNotFoundException("Workflow loading not yet implemented: " + workflowId);
    }

    // --- Result types ---

    /// Result of starting an execution.
    public record ExecutionStartResult(String executionId, String workflowId) {}

    /// Decision for resuming a paused execution.
    public record ResumeDecision(boolean approved, Map<String, Object> modifications) {
        public static ResumeDecision approve() {
            return new ResumeDecision(true, Map.of());
        }

        public static ResumeDecision modify(Map<String, Object> mods) {
            return new ResumeDecision(true, mods);
        }
    }

    /// Information about a pending plan.
    public record PlanInfo(String planId, int totalSteps, int currentStep) {}

    /// Status of an execution.
    public record ExecutionStatus(
            String executionId,
            String workflowId,
            String status,
            String currentNodeId,
            boolean hasPendingPlan) {}

    /// Summary of an execution.
    public record ExecutionSummary(
            String executionId,
            String workflowId,
            String currentNodeId,
            java.time.Instant createdAt) {}

    // --- Exceptions ---

    /// Thrown when a workflow is not found.
    public static class WorkflowNotFoundException extends RuntimeException {
        @Serial private static final long serialVersionUID = 6275906519265300703L;

        public WorkflowNotFoundException(String message) {
            super(message);
        }
    }

    /// Thrown when an execution is not found.
    public static class ExecutionNotFoundException extends RuntimeException {
        @Serial private static final long serialVersionUID = -4295834705797331331L;

        public ExecutionNotFoundException(String message) {
            super(message);
        }
    }

    /// Thrown when workflow execution fails.
    public static class WorkflowExecutionException extends RuntimeException {
        @Serial private static final long serialVersionUID = 2286066982933451717L;

        public WorkflowExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
