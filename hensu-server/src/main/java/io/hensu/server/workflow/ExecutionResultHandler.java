package io.hensu.server.workflow;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
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
                                null,
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
                log.warnv("Unexpected Success from {0}: executionId={1}", callerLabel, executionId);
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
}
