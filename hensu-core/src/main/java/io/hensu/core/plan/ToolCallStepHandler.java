package io.hensu.core.plan;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/// {@link StepHandler} that executes {@link PlanStepAction.ToolCall} steps
/// via an {@link ActionExecutor}.
///
/// Each tool-call step translates to an {@link Action.Send} dispatched through
/// the configured action executor. The result is mapped to a {@link StepResult}.
///
/// @implNote Thread-safe. Stateless beyond the injected {@code ActionExecutor}.
///
/// @see SynthesizeStepHandler for the synthesis counterpart
/// @see StepHandlerRegistry for registration
public class ToolCallStepHandler implements StepHandler<PlanStepAction.ToolCall> {

    private final ActionExecutor actionExecutor;

    /// Creates a handler backed by the given action executor.
    ///
    /// @param actionExecutor the action executor, not null
    public ToolCallStepHandler(ActionExecutor actionExecutor) {
        this.actionExecutor =
                Objects.requireNonNull(actionExecutor, "actionExecutor must not be null");
    }

    @Override
    public Class<PlanStepAction.ToolCall> getActionType() {
        return PlanStepAction.ToolCall.class;
    }

    @Override
    public StepResult handle(
            PlannedStep step, PlanStepAction.ToolCall action, Map<String, Object> context) {
        Instant start = Instant.now();
        Action mcpAction = new Action.Send(action.toolName(), action.arguments());
        ActionResult result = actionExecutor.execute(mcpAction, context);
        Duration duration = Duration.between(start, Instant.now());

        if (result.success()) {
            return StepResult.success(step.index(), action.toolName(), result.output(), duration);
        } else {
            return StepResult.failure(step.index(), action.toolName(), result.message(), duration);
        }
    }
}
