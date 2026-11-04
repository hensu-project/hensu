package io.hensu.server.workflow;

import io.hensu.core.resume.ResumeInput;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.LogSanitizer;
import io.hensu.core.workflow.Workflow;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.workflow.ExecutionRunner.RunOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Objects;
import org.jboss.logging.Logger;

/// Resumes executions paused for review.
///
/// Any node serves a resume: the claim is taken in the store, which is the same for every
/// node, and the resumed run reaches the tenant's tools through the store as well.
@ApplicationScoped
public class ExecutionStateService {

    private static final Logger LOG = Logger.getLogger(ExecutionStateService.class);

    private final ExecutionStore store;
    private final ExecutionRunner runner;
    private final WorkflowRegistryService registryService;

    @Inject
    public ExecutionStateService(
            ExecutionStore store, ExecutionRunner runner, WorkflowRegistryService registryService) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.registryService =
                Objects.requireNonNull(registryService, "registryService must not be null");
    }

    /// Resumes a paused execution and runs it on this thread until it settles or stops.
    ///
    /// The claim is taken first and the state read under it, so the run resumes from the
    /// state the claim found rather than one read before a concurrent resume changed it. A
    /// resume input that does not fit the paused phase is a bad request, not a broken
    /// execution: the execution goes back to waiting and the caller gets a 400.
    ///
    /// @param tenantId the tenant owning the execution, not null
    /// @param executionId the execution to resume, not null
    /// @param resumeInput the caller-supplied input for the resumed execution, may be null
    /// @throws ExecutionNotFoundException if the execution does not exist
    /// @throws ExecutionNotPausedException if it exists but is not waiting for a resume
    /// @throws WorkflowExecutionException if the resumed run fails
    public void resumeExecution(String tenantId, String executionId, ResumeInput resumeInput) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");

        LOG.infov(
                "Resuming workflow execution: executionId={0}, tenant={1}",
                LogSanitizer.sanitize(executionId), tenantId);

        Claim claim =
                store.resume(tenantId, executionId)
                        .orElseThrow(
                                () ->
                                        store.findByExecutionId(tenantId, executionId).isPresent()
                                                ? new ExecutionNotPausedException(
                                                        "Execution "
                                                                + executionId
                                                                + " is not paused for review")
                                                : new ExecutionNotFoundException(
                                                        "Execution not found: " + executionId));

        HensuSnapshot snapshot =
                store.findByExecutionId(tenantId, executionId)
                        .orElseThrow(
                                () ->
                                        new ExecutionNotFoundException(
                                                "Execution not found: " + executionId));
        try {
            ExecutionPhase.validateCorrelation(snapshot.phase(), resumeInput);
        } catch (IllegalArgumentException e) {
            store.pause(claim, snapshot);
            throw e;
        }

        HensuState state = snapshot.toState();
        state.setResumeInput(resumeInput);
        if (resumeInput instanceof ResumeInput.ApplyContextEdits(Map<String, Object> edits)) {
            state.getContext().putAll(edits);
        }
        Workflow workflow = registryService.getWorkflow(tenantId, snapshot.workflowId());

        if (runner.run(claim, state, workflow) == RunOutcome.FAILED) {
            throw new WorkflowExecutionException(
                    "Resume failed for execution " + executionId, null);
        }
    }
}
