package io.hensu.server.workflow;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.persistence.ExecutionLeaseManager;
import io.hensu.server.tenant.TenantContext;
import io.hensu.server.tenant.TenantContext.TenantInfo;
import io.hensu.server.validation.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
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

    @Inject
    public ExecutionStateService(
            WorkflowExecutor workflowExecutor,
            WorkflowStateRepository stateRepository,
            WorkflowRegistryService registryService,
            ExecutionLeaseManager leaseManager) {
        this.workflowExecutor =
                Objects.requireNonNull(workflowExecutor, "workflowExecutor must not be null");
        this.stateRepository =
                Objects.requireNonNull(stateRepository, "stateRepository must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager must not be null");
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
    /// @param decision the resume decision (approve/modify plan), may be null
    /// @throws ExecutionNotFoundException if execution not found
    /// @throws IllegalStateException if another node currently owns the execution lease
    /// @throws WorkflowExecutionException if resume fails
    public void resumeExecution(String tenantId, String executionId, ResumeDecision decision) {
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

            try {
                TenantContext.runAs(
                        tenant,
                        () -> {
                            HensuState state = snapshot.toState();

                            if (decision != null && decision.modifications() != null) {
                                state.getContext().putAll(decision.modifications());
                            }

                            Workflow workflow =
                                    registryService.getWorkflow(tenantId, snapshot.workflowId());

                            ExecutionResult result =
                                    workflowExecutor.executeFrom(
                                            workflow, state, checkpointListener(tenantId));

                            if (result instanceof ExecutionResult.Completed completed) {
                                stateRepository.save(
                                        tenantId,
                                        HensuSnapshot.from(completed.finalState(), "completed"));
                            } else if (result
                                    instanceof ExecutionResult.Paused(HensuState pausedState)) {
                                stateRepository.save(
                                        tenantId, HensuSnapshot.from(pausedState, "paused"));
                            } else if (result instanceof ExecutionResult.Rejected rejected) {
                                stateRepository.save(
                                        tenantId, HensuSnapshot.from(rejected.state(), "rejected"));
                            } else if (result
                                    instanceof ExecutionResult.Failure(HensuState failedState, _)) {
                                stateRepository.save(
                                        tenantId, HensuSnapshot.from(failedState, "failed"));
                            }

                            return null;
                        });
            } catch (Exception e) {
                LOG.errorv(
                        e,
                        "Resume execution failed: executionId={0}",
                        LogSanitizer.sanitize(executionId));
                throw new WorkflowExecutionException("Resume failed: " + e.getMessage(), e);
            }
        } finally {
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
}
