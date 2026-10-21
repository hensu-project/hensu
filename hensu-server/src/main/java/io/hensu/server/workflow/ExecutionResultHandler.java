package io.hensu.server.workflow;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.core.util.LogSanitizer;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import org.jboss.logging.Logger;

/// Shared handler for persisting and publishing {@link ExecutionResult} outcomes.
///
/// Both {@link WorkflowExecutionService} (new executions) and {@link ExecutionStateService}
/// (resumed executions) produce the same five {@link ExecutionResult} variants and need
/// identical save-then-publish logic. This class eliminates that duplication.
final class ExecutionResultHandler {

    private ExecutionResultHandler() {}

    /// Persists the result snapshot and publishes the corresponding SSE event.
    ///
    /// @param result the execution result to handle, not null
    /// @param tenantId the owning tenant, not null
    /// @param executionId the execution identifier, not null
    /// @param workflowId the workflow identifier, not null
    /// @param stateRepository repository for snapshot persistence, not null
    /// @param eventBroadcaster broadcaster for SSE events, not null
    /// @param log logger for the calling service, not null
    /// @param callerLabel label for log messages (e.g. {@code "execute"} or {@code "executeFrom"})
    static void handle(
            ExecutionResult result,
            String tenantId,
            String executionId,
            String workflowId,
            WorkflowStateRepository stateRepository,
            ExecutionEventBroadcaster eventBroadcaster,
            Logger log,
            String callerLabel) {
        switch (result) {
            case ExecutionResult.Completed(HensuState finalState, _) -> {
                stateRepository.save(tenantId, HensuSnapshot.from(finalState, "completed"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionCompleted.success(
                                executionId,
                                workflowId,
                                finalState.getCurrentNode(),
                                WorkflowContextUtil.publicContext(finalState.getContext())));
            }
            case ExecutionResult.Rejected(_, HensuState rejectedState) -> {
                stateRepository.save(tenantId, HensuSnapshot.from(rejectedState, "rejected"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionCompleted.failure(
                                executionId,
                                workflowId,
                                rejectedState.getCurrentNode(),
                                WorkflowContextUtil.publicContext(rejectedState.getContext())));
            }
            case ExecutionResult.Paused(HensuState pausedState) -> {
                stateRepository.save(tenantId, HensuSnapshot.from(pausedState, "paused"));
                String correlationId =
                        pausedState.getPhase() instanceof ExecutionPhase.Awaiting awaiting
                                ? awaiting.correlationId()
                                : null;
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionPaused.now(
                                executionId,
                                workflowId,
                                pausedState.getCurrentNode(),
                                correlationId,
                                "review",
                                WorkflowContextUtil.publicContext(pausedState.getContext())));
            }
            case ExecutionResult.Failure(HensuState failedState, IllegalStateException e) -> {
                stateRepository.save(tenantId, HensuSnapshot.from(failedState, "failed"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionError.now(
                                executionId,
                                "ExecutionFailure",
                                e.getMessage(),
                                failedState.getCurrentNode()));
            }
            case ExecutionResult.Success(HensuState unexpectedState) -> {
                log.warnv(
                        "Unexpected Success from {0}: executionId={1}",
                        callerLabel, LogSanitizer.sanitize(executionId));
                stateRepository.save(tenantId, HensuSnapshot.from(unexpectedState, "failed"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionError.now(
                                executionId,
                                "UnexpectedResult",
                                callerLabel + " returned intermediate Success",
                                unexpectedState.getCurrentNode()));
            }
        }
    }

    /// Records a run that failed outside {@link #handle}, without letting the
    /// write replace the failure being reported.
    ///
    /// The write can be refused: if the run got as far as saving a terminal
    /// state before something else threw, the row is no longer leased to this
    /// node, and the fenced save answers with a `LeaseLostException`. The
    /// execution's recorded state then stands – a run that completed is not
    /// rewritten as failed by a broken event stream – and the refusal, like any
    /// other write failure here, is attached to `cause` as suppressed rather
    /// than thrown in its place.
    ///
    /// @param stateRepository repository for snapshot persistence, not null
    /// @param tenantId the owning tenant, not null
    /// @param failed the snapshot to record, with reason `failed`, not null
    /// @param cause the failure being reported, not null
    /// @param log logger for the calling service, not null
    static void saveFailed(
            WorkflowStateRepository stateRepository,
            String tenantId,
            HensuSnapshot failed,
            Throwable cause,
            Logger log) {
        try {
            stateRepository.save(tenantId, failed);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.warnv(
                    "Execution {0} not recorded as failed: {1}",
                    LogSanitizer.sanitize(failed.executionId()), e.getMessage());
        }
    }
}
