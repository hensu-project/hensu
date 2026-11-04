package io.hensu.server.workflow;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.LogSanitizer;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import org.jboss.logging.Logger;

/// Records a run's outcome under its claim and publishes the matching event.
///
/// Every write is fenced on the claim: a run that lost its claim cannot record a pause
/// or a terminal state over the run that superseded it, and the
/// {@link io.hensu.core.execution.RunPreemptedException} the store throws propagates to
/// the runner, which stops without recording anything.
final class ExecutionResultHandler {

    private ExecutionResultHandler() {}

    /// Persists the result under the claim and publishes the corresponding event.
    ///
    /// @param result the execution result to handle, not null
    /// @param claim the run's claim, not null
    /// @param workflowId the workflow identifier, not null
    /// @param store where the outcome is recorded, not null
    /// @param eventBroadcaster broadcaster for execution events, not null
    /// @param log logger for the calling service, not null
    static void handle(
            ExecutionResult result,
            Claim claim,
            String workflowId,
            ExecutionStore store,
            ExecutionEventBroadcaster eventBroadcaster,
            Logger log) {
        String executionId = claim.executionId();
        switch (result) {
            case ExecutionResult.Completed(HensuState finalState, _) -> {
                store.finish(claim, HensuSnapshot.from(finalState, "completed"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionCompleted.success(
                                executionId,
                                workflowId,
                                finalState.getCurrentNode(),
                                WorkflowContextUtil.publicContext(finalState.getContext())));
            }
            case ExecutionResult.Rejected(_, HensuState rejectedState) -> {
                store.finish(claim, HensuSnapshot.from(rejectedState, "rejected"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionCompleted.failure(
                                executionId,
                                workflowId,
                                rejectedState.getCurrentNode(),
                                WorkflowContextUtil.publicContext(rejectedState.getContext())));
            }
            case ExecutionResult.Paused(HensuState pausedState) -> {
                store.pause(claim, HensuSnapshot.from(pausedState, "paused"));
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
                store.finish(claim, HensuSnapshot.from(failedState, "failed"));
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
                        "Unexpected Success for execution {0}", LogSanitizer.sanitize(executionId));
                store.finish(claim, HensuSnapshot.from(unexpectedState, "failed"));
                eventBroadcaster.publish(
                        executionId,
                        ExecutionEvent.ExecutionError.now(
                                executionId,
                                "UnexpectedResult",
                                "run returned intermediate Success",
                                unexpectedState.getCurrentNode()));
            }
        }
    }

    /// Records a run that failed outside {@link #handle}, without letting the write
    /// replace the failure being reported.
    ///
    /// The write can be refused – the claim may have been superseded while the run was
    /// failing – and then the execution's recorded state stands. The refusal, like any
    /// other write failure here, is attached to `cause` as suppressed rather than thrown
    /// in its place.
    ///
    /// @param store where the outcome is recorded, not null
    /// @param claim the run's claim, not null
    /// @param failed the snapshot to record, with reason `failed`, not null
    /// @param cause the failure being reported, not null
    /// @param log logger for the calling service, not null
    static void saveFailed(
            ExecutionStore store, Claim claim, HensuSnapshot failed, Throwable cause, Logger log) {
        try {
            store.finish(claim, failed);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.warnv(
                    "Execution {0} not recorded as failed: {1}",
                    LogSanitizer.sanitize(claim.executionId()), e.getMessage());
        }
    }
}
