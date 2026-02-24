package io.hensu.server.service;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serial;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/// Service for workflow execution and state management.
///
/// Handles business logic for workflow operations:
/// - Starting new workflow executions
/// - Resuming paused executions
/// - Retrieving execution state, plans, and final output
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
    private final WorkflowRepository workflowRepository;

    @Inject
    public WorkflowService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            ExecutionEventBroadcaster eventBroadcaster,
            WorkflowRepository workflowRepository) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
        this.workflowRepository =
                Objects.requireNonNull(workflowRepository, "workflowRepository must not be null");
    }

    /// Accepts a new workflow execution and dispatches it asynchronously.
    ///
    /// Validates that the workflow exists, then immediately returns an execution ID while
    /// running the workflow on a background virtual thread. The caller does not block on
    /// workflow completion — progress and outcome are delivered via SSE events.
    ///
    /// ### Contracts
    /// - **Precondition**: workflow identified by `workflowId` must be registered for the tenant
    /// - **Postcondition**: `ExecutionStarted` SSE event is published before this method returns
    ///
    /// @apiNote **Side effects**:
    /// - Publishes an `ExecutionStarted` event to the SSE stream synchronously
    /// - Dispatches a virtual thread that persists checkpoints and publishes completion events
    /// - Background thread publishes `ExecutionCompleted` on success (with `output` — the
    ///   public workflow context, all keys prefixed with `_` stripped) or `ExecutionError`
    ///   on unrecoverable failure
    ///
    /// @implNote Execution runs on a dedicated `Thread.ofVirtual()` thread; the HTTP response
    /// is returned before the workflow completes. Both {@link TenantContext} and the
    /// execution-scoped broadcaster are explicitly rebound inside the background thread.
    ///
    /// @param tenantId the tenant requesting execution; typically the JWT {@code tenant_id}
    ///        claim resolved by {@code RequestTenantResolver} at the API layer, not null
    /// @param workflowId the workflow to execute, not null
    /// @param context initial context variables, not null
    /// @return execution result containing the assigned execution ID, never null
    /// @throws WorkflowNotFoundException if the workflow does not exist
    public ExecutionStartResult startExecution(
            String tenantId, String workflowId, Map<String, Object> context) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        LOG.infov("Accepting workflow execution: workflow={0}, tenant={1}", workflowId, tenantId);

        String executionId = UUID.randomUUID().toString();

        // Preserve existing tenant context (e.g., with MCP endpoint) if already bound;
        // otherwise create a simple context for this tenant.
        TenantInfo tenant =
                TenantContext.isBound() && TenantContext.current().tenantId().equals(tenantId)
                        ? TenantContext.current()
                        : TenantInfo.simple(tenantId);

        // Validate workflow existence synchronously — fail fast before accepting the request.
        workflowRepository
                .findById(tenantId, workflowId)
                .orElseThrow(
                        () -> new WorkflowNotFoundException("Workflow not found: " + workflowId));

        Map<String, Object> executionContext = new HashMap<>(context);
        executionContext.put("_tenant_id", tenantId);
        executionContext.put("_execution_id", executionId);

        eventBroadcaster.publish(
                executionId,
                ExecutionEvent.ExecutionStarted.now(executionId, workflowId, tenantId));

        // Dispatch execution on a virtual thread — HTTP response returns immediately.
        Thread.ofVirtual()
                .name("wf-exec-" + executionId)
                .start(
                        () ->
                                runExecutionAsync(
                                        executionId,
                                        workflowId,
                                        tenantId,
                                        tenant,
                                        executionContext));

        LOG.infov("Workflow execution accepted: executionId={0}", executionId);
        return new ExecutionStartResult(executionId, workflowId);
    }

    /// Runs a workflow execution on the calling (virtual) thread.
    ///
    /// Rebinds both {@link TenantContext} and execution-scoped broadcaster before executing,
    /// then handles all result variants by persisting state and publishing the appropriate event.
    /// Always calls {@link ExecutionEventBroadcaster#complete} in a `finally` block to
    /// close the SSE stream regardless of outcome.
    ///
    /// @param executionId the execution identifier, not null
    /// @param workflowId the workflow to execute, not null
    /// @param tenantId the owning tenant, not null
    /// @param tenant the tenant info for scoped context, not null
    /// @param executionContext the pre-built execution context, not null
    private void runExecutionAsync(
            String executionId,
            String workflowId,
            String tenantId,
            TenantInfo tenant,
            Map<String, Object> executionContext) {
        AtomicReference<HensuState> lastCheckpoint = new AtomicReference<>();
        try {
            eventBroadcaster.runAs(
                    executionId,
                    () -> {
                        TenantContext.runAs(
                                tenant,
                                () -> {
                                    Workflow workflow = loadWorkflow(workflowId);

                                    ExecutionResult result =
                                            workflowExecutor.execute(
                                                    workflow,
                                                    executionContext,
                                                    trackingCheckpointListener(
                                                            tenantId, lastCheckpoint));

                                    if (result instanceof ExecutionResult.Completed completed) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(
                                                        completed.finalState(), "completed");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.success(
                                                        executionId,
                                                        workflowId,
                                                        completed.finalState().getCurrentNode(),
                                                        publicContext(
                                                                completed
                                                                        .finalState()
                                                                        .getContext())));
                                    } else if (result
                                            instanceof ExecutionResult.Rejected rejected) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(rejected.state(), "rejected");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.failure(
                                                        executionId,
                                                        workflowId,
                                                        rejected.state().getCurrentNode(),
                                                        publicContext(
                                                                rejected.state().getContext())));
                                    } else if (result
                                            instanceof ExecutionResult.Paused(HensuState state)) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(state, "paused");
                                        stateRepository.save(tenantId, snapshot);
                                        eventBroadcaster.publish(
                                                executionId,
                                                ExecutionEvent.ExecutionCompleted.failure(
                                                        executionId,
                                                        workflowId,
                                                        state.getCurrentNode(),
                                                        publicContext(state.getContext())));
                                    } else if (result
                                            instanceof
                                            ExecutionResult.Failure(
                                                    HensuState currentState,
                                                    IllegalStateException e)) {
                                        HensuSnapshot snapshot =
                                                HensuSnapshot.from(currentState, "failed");
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
                        return null;
                    });
            LOG.infov("Workflow execution completed: executionId={0}", executionId);
        } catch (Exception e) {
            LOG.errorv(
                    e, "Workflow execution failed: workflow={0}, tenant={1}", workflowId, tenantId);
            eventBroadcaster.publish(
                    executionId,
                    ExecutionEvent.ExecutionError.now(
                            executionId, e.getClass().getSimpleName(), e.getMessage(), null));
            // Persist a terminal "failed" snapshot so that status queries return "failed"
            // rather than a stale "checkpoint" or a missing record, and so that
            // WorkflowRecoveryJob does not attempt to re-claim an execution that ended
            // with an unrecoverable error. Mirrors the ExecutionResult.Failure branch above,
            // which handles failures that the executor wraps; this handles those that escape.
            HensuState ls = lastCheckpoint.get();
            if (ls != null) {
                stateRepository.save(tenantId, HensuSnapshot.from(ls, "failed"));
            }
        } finally {
            eventBroadcaster.complete(executionId);
        }
    }

    /// Creates a checkpoint listener that persists state after each node and tracks the latest.
    ///
    /// @param tenantId the owning tenant, not null
    /// @param lastCheckpoint holder updated with every checkpoint state, not null
    /// @return listener that saves checkpoints and updates the reference, never null
    private ExecutionListener trackingCheckpointListener(
            String tenantId, AtomicReference<HensuState> lastCheckpoint) {
        return new ExecutionListener() {
            @Override
            public void onCheckpoint(HensuState state) {
                lastCheckpoint.set(state);
                stateRepository.save(tenantId, HensuSnapshot.from(state, "checkpoint"));
            }
        };
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

                        // Apply modifications from resume decision
                        if (decision != null && decision.modifications() != null) {
                            state.getContext().putAll(decision.modifications());
                        }

                        // Load the workflow for re-execution
                        Workflow workflow = loadWorkflow(snapshot.workflowId());

                        // Resume execution from saved state
                        ExecutionResult result =
                                workflowExecutor.executeFrom(
                                        workflow, state, checkpointListener(tenantId));

                        // Save final state
                        if (result instanceof ExecutionResult.Completed completed) {
                            HensuSnapshot finalSnapshot =
                                    HensuSnapshot.from(completed.finalState(), "completed");
                            stateRepository.save(tenantId, finalSnapshot);
                        } else if (result
                                instanceof ExecutionResult.Paused(HensuState pausedState)) {
                            HensuSnapshot pausedSnapshot =
                                    HensuSnapshot.from(pausedState, "paused");
                            stateRepository.save(tenantId, pausedSnapshot);
                        } else if (result instanceof ExecutionResult.Rejected rejected) {
                            HensuSnapshot rejectedSnapshot =
                                    HensuSnapshot.from(rejected.state(), "rejected");
                            stateRepository.save(tenantId, rejectedSnapshot);
                        } else if (result
                                instanceof ExecutionResult.Failure(HensuState failedState, _)) {
                            HensuSnapshot failedSnapshot =
                                    HensuSnapshot.from(failedState, "failed");
                            stateRepository.save(tenantId, failedSnapshot);
                        }

                        return null;
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
    /// @return the plan if one is pending review, empty if no plan is active
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
    /// @return the execution status, never null
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

    /// Gets the public output of a completed or paused execution.
    ///
    /// Returns the workflow context at the time of the last checkpoint with
    /// internal system keys (prefixed with `_`) filtered out. This is the
    /// primary endpoint for retrieving the final result of a workflow run.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution ID, not null
    /// @return the execution output, never null
    /// @throws ExecutionNotFoundException if execution not found
    public ExecutionOutput getExecutionResult(String tenantId, String executionId) {
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
        return new ExecutionOutput(
                executionId, snapshot.workflowId(), status, publicContext(snapshot.context()));
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
                                        s.createdAt()))
                .toList();
    }

    /// Returns only the public context entries — keys starting with `_` are internal
    /// system variables and are excluded from client-facing output.
    ///
    /// @param context the raw execution context, not null
    /// @return unmodifiable map of public context entries, never null, may be empty
    private static Map<String, Object> publicContext(Map<String, Object> context) {
        return context.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /// Creates a listener that persists workflow state after each node completes.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @return listener that saves checkpoints to the state repository, never null
    private ExecutionListener checkpointListener(String tenantId) {
        return new ExecutionListener() {
            @Override
            public void onCheckpoint(HensuState state) {
                stateRepository.save(tenantId, HensuSnapshot.from(state, "checkpoint"));
            }
        };
    }

    /// Loads a workflow by ID.
    ///
    /// @param workflowId the workflow ID to load, not null
    /// @return the workflow, never null
    /// @throws WorkflowNotFoundException if not found
    private Workflow loadWorkflow(String workflowId) {
        String tenantId = TenantContext.current().tenantId();
        return workflowRepository
                .findById(tenantId, workflowId)
                .orElseThrow(
                        () -> new WorkflowNotFoundException("Workflow not found: " + workflowId));
    }

    // --- Result types ---

    /// Result of starting an execution.
    ///
    /// @param executionId the assigned execution identifier, never null
    /// @param workflowId the workflow that was started, never null
    public record ExecutionStartResult(String executionId, String workflowId) {}

    /// Decision for resuming a paused execution.
    ///
    /// @param approved whether the pending plan is approved
    /// @param modifications optional context modifications to apply before resuming, may be null
    public record ResumeDecision(boolean approved, Map<String, Object> modifications) {
        public static ResumeDecision approve() {
            return new ResumeDecision(true, Map.of());
        }

        public static ResumeDecision modify(Map<String, Object> mods) {
            return new ResumeDecision(true, mods);
        }
    }

    /// Information about a pending plan.
    ///
    /// @param planId the plan identifier, never null
    /// @param totalSteps total number of steps in the plan
    /// @param currentStep index of the step currently executing
    public record PlanInfo(String planId, int totalSteps, int currentStep) {}

    /// Status of an execution.
    ///
    /// @param executionId the execution identifier, never null
    /// @param workflowId the workflow definition identifier, never null
    /// @param status `COMPLETED` or `PAUSED`, never null
    /// @param currentNodeId the node where execution is positioned, may be null if completed
    /// @param hasPendingPlan true if a plan is awaiting review
    public record ExecutionStatus(
            String executionId,
            String workflowId,
            String status,
            String currentNodeId,
            boolean hasPendingPlan) {}

    /// The public output of a completed or paused execution.
    ///
    /// Contains the workflow context at termination with internal system keys
    /// (prefixed with `_`) excluded.
    ///
    /// @param executionId the execution identifier, never null
    /// @param workflowId the workflow definition identifier, never null
    /// @param status `COMPLETED` or `PAUSED`, never null
    /// @param output public context variables produced by the workflow, never null, may be empty
    public record ExecutionOutput(
            String executionId, String workflowId, String status, Map<String, Object> output) {}

    /// Summary of an execution.
    ///
    /// @param executionId the execution identifier, never null
    /// @param workflowId the workflow definition identifier, never null
    /// @param currentNodeId the node where execution is paused, may be null
    /// @param createdAt when the execution was created, never null
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
