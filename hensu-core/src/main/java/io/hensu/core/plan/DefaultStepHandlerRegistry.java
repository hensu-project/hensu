package io.hensu.core.plan;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// Default mutable implementation of {@link StepHandlerRegistry}.
///
/// Handlers are stored in a plain {@link HashMap}; registration is not
/// thread-safe and must be completed before the registry is shared across
/// threads.  Dispatch (read-only after setup) is safe from multiple threads.
///
/// ### Contracts
/// - **Precondition**: all handlers must be registered before
///   {@link #dispatch(PlannedStep, Map)} is first called
/// - **Postcondition**: {@link #dispatch} never returns null; returns a
///   failure {@link StepResult} for unknown action types
///
/// @implNote Not thread-safe for concurrent registration. Safe for
/// concurrent dispatch after registration is complete.
///
/// @see StepHandlerRegistry
public class DefaultStepHandlerRegistry implements StepHandlerRegistry {

    private final Map<Class<? extends PlanStepAction>, StepHandler<?>> registry = new HashMap<>();

    @Override
    public <A extends PlanStepAction> void register(StepHandler<A> handler) {
        registry.put(handler.getActionType(), handler);
    }

    @Override
    public <A extends PlanStepAction> Optional<StepHandler<A>> getHandler(Class<A> actionType) {
        return Optional.ofNullable((StepHandler<A>) registry.get(actionType));
    }

    @Override
    public StepResult dispatch(PlannedStep step, Map<String, Object> context) {
        PlanStepAction action = step.action();
        StepHandler handler = registry.get(action.getClass());
        if (handler == null) {
            return StepResult.failure(
                    step.index(),
                    action.getClass().getSimpleName(),
                    "No handler registered for action type: " + action.getClass().getSimpleName(),
                    Duration.ZERO);
        }
        return handler.handle(step, action, context);
    }
}
