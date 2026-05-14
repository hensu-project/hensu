package io.hensu.server.workflow;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.util.LogSanitizer;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/// Service for resuming paused workflow executions and managing checkpoint persistence.
///
/// Loads a previously paused execution from the state repository, applies any context
/// modifications from the resume decision, and hands the state back to the core executor
/// via {@link WorkflowExecutor#executeFrom}. Handles the same four {@link ExecutionResult}
/// variants as {@link WorkflowExecutionService} and persists the resulting snapshot.
@ApplicationScoped
public class ExecutionStateService {

    private static final Logger LOG = Logger.getLogger(ExecutionStateService.class);

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowStateRepository stateRepository;
    private final WorkflowRegistryService registryService;
    private final ExecutionLeaseManager leaseManager;
    private final ExecutionEventBroadcaster eventBroadcaster;

    @Inject
    public ExecutionStateService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            WorkflowRegistryService registryService,
            ExecutionLeaseManager leaseManager,
            ExecutionEventBroadcaster eventBroadcaster) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager must not be null");
        this.eventBroadcaster =
                Objects.requireNonNull(eventBroadcaster, "eventBroadcaster must not be null");
    }

    /// Resumes a paused workflow execution.
    ///
    /// Acquires the execution lease before driving the executor and releases it in a
    /// `finally` block — closes the split-brain window where an API-edge resume could
    /// run concurrently with a recovery sweep on another node. The claim is re-entrant
    /// for this node, so the recovery → resume hand-off works without releasing in
    /// between.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution to resume, not null
    /// @param resumeInput the caller-supplied input for the resumed execution, may be null
    /// @throws ExecutionNotFoundException if execution not found
    /// @throws IllegalStateException if another node currently owns the execution lease
    /// @throws WorkflowExecutionException if resume fails
    public void resumeExecution(String tenantId, String executionId, ResumeInput resumeInput) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        LOG.infov(
                "Resuming workflow execution: executionId={0}, tenant={1}",
                LogSanitizer.sanitize(executionId), tenantId);

        if (!leaseManager.tryClaim(tenantId, executionId)) {
            throw new IllegalStateException("Execution owned by another node: " + executionId);
        }

        try {
            HensuSnapshot snapshot =
                    stateRepository
                            .findByExecutionId(tenantId, executionId)
                            .orElseThrow(
                                    () ->
                                            new ExecutionNotFoundException(
                                                    "Execution not found: " + executionId));

            TenantInfo tenant = TenantInfo.simple(tenantId);
            String workflowId = snapshot.workflowId();

            try {
                eventBroadcaster.runAs(
                        executionId,
                        () -> {
                            TenantContext.runAs(
                                    tenant,
                                    () -> {
                                        HensuState state = snapshot.toState();
                                        ExecutionPhase.validateCorrelation(
                                                state.getPhase(), resumeInput);
                                        state.setResumeInput(resumeInput);

                                        if (resumeInput
                                                instanceof
                                                ResumeInput.ApplyContextEdits(
                                                        Map<String, Object> edits1)) {
                                            state.getContext().putAll(edits1);
                                        }

                                        Workflow workflow =
                                                registryService.getWorkflow(tenantId, workflowId);

                                        ExecutionResult result =
                                                workflowExecutor.executeFrom(
                                                        workflow,
                                                        state,
                                                        checkpointListener(tenantId));

                                        switch (result) {
                                            case ExecutionResult.Completed(var finalState, _) -> {
                                                stateRepository.save(
                                                        tenantId,
                                                        HensuSnapshot.from(
                                                                finalState, "completed"));
                                                eventBroadcaster.publish(
                                                        executionId,
                                                        ExecutionEvent.ExecutionCompleted.success(
                                                                executionId,
                                                                workflowId,
                                                                finalState.getCurrentNode(),
                                                                publicContext(
                                                                        finalState.getContext())));
                                            }
                                            case ExecutionResult.Paused(var pausedState) -> {
                                                stateRepository.save(
                                                        tenantId,
                                                        HensuSnapshot.from(pausedState, "paused"));
                                                String correlationId =
                                                        pausedState.getPhase()
                                                                        instanceof
                                                                        ExecutionPhase.Awaiting
                                                                                awaiting
                                                                ? awaiting.correlationId()
                                                                : null;
                                                eventBroadcaster.publish(
                                                        executionId,
                                                        ExecutionEvent.ExecutionPaused.now(
                                                                executionId,
                                                                workflowId,
                                                                pausedState.getCurrentNode(),
                                                                null,
                                                                correlationId,
                                                                "review",
                                                                publicContext(
                                                                        pausedState.getContext())));
                                            }
                                            case ExecutionResult.Rejected(_, var rejectedState) -> {
                                                stateRepository.save(
                                                        tenantId,
                                                        HensuSnapshot.from(
                                                                rejectedState, "rejected"));
                                                eventBroadcaster.publish(
                                                        executionId,
                                                        ExecutionEvent.ExecutionCompleted.failure(
                                                                executionId,
                                                                workflowId,
                                                                rejectedState.getCurrentNode(),
                                                                publicContext(
                                                                        rejectedState
                                                                                .getContext())));
                                            }
                                            case ExecutionResult.Failure(
                                                            var failedState,
                                                            var e) -> {
                                                stateRepository.save(
                                                        tenantId,
                                                        HensuSnapshot.from(failedState, "failed"));
                                                eventBroadcaster.publish(
                                                        executionId,
                                                        ExecutionEvent.ExecutionError.now(
                                                                executionId,
                                                                "ExecutionFailure",
                                                                e.getMessage(),
                                                                failedState.getCurrentNode()));
                                            }
                                            case ExecutionResult.Success _ -> {
                                                // Intermediate result — not expected from
                                                // executeFrom
                                            }
                                        }

                                        return null;
                                    });
                            return null;
                        });
            } catch (Exception e) {
                LOG.errorv(
                        e,
                        "Resume execution failed: executionId={0}",
                        LogSanitizer.sanitize(executionId));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionError.now(
                                executionId, e.getClass().getSimpleName(), e.getMessage(), null));
                throw new WorkflowExecutionException("Resume failed: " + e.getMessage(), e);
            }
        } finally {
            eventBroadcaster.complete(executionId);
            leaseManager.release(tenantId, executionId);
        }
    }

    private ExecutionListener checkpointListener(String tenantId) {
        return new ExecutionListener() {
            @Override
            public void onCheckpoint(HensuState state) {
                stateRepository.save(tenantId, HensuSnapshot.from(state, "checkpoint"));
            }
        };
    }

    private static Map<String, Object> publicContext(Map<String, Object> context) {
        return context.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
