package io.hensu.core.plan;

import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.plan.PlanEvent.PlanCompleted;
import io.hensu.core.plan.PlanEvent.PlanCreated;
import io.hensu.core.plan.PlanEvent.StepCompleted;
import io.hensu.core.plan.PlanEvent.StepStarted;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/// Executes plans step by step using registered action handlers.
///
/// The executor iterates through plan steps, invoking the appropriate
/// {@link ActionExecutor} for each step. Events are emitted to registered
/// observers for monitoring and logging.
///
/// ### Execution Flow
/// 1. Emit `PlanCreated` event
/// 2. For each step:
///    - Emit `StepStarted` event
///    - Execute step via ActionExecutor
///    - Emit `StepCompleted` event
///    - On failure: return failed result (no automatic retry)
/// 3. Emit `PlanCompleted` event
/// 4. Return aggregated result
///
/// ### Thread Safety
/// @implNote Thread-safe for observer registration. Execution should occur
/// from a single thread per plan execution.
///
/// ### Usage
/// {@snippet :
/// PlanExecutor executor = new PlanExecutor(actionExecutor);
/// executor.addObserver(event -> log.info("Event: " + event));
///
/// Plan plan = Plan.staticPlan("node", steps);
/// PlanResult result = executor.execute(plan, Map.of("orderId", "123"));
///
/// if (result.isSuccess()) {
///     process(result.output());
/// }
/// }
///
/// @see Plan for plan structure
/// @see PlanResult for execution outcome
/// @see PlanObserver for event handling
public class PlanExecutor {

    private final ActionExecutor actionExecutor;
    private final List<PlanObserver> observers = new CopyOnWriteArrayList<>();

    /// Creates an executor with the given action executor.
    ///
    /// @param actionExecutor executor for individual steps, not null
    /// @throws NullPointerException if actionExecutor is null
    public PlanExecutor(ActionExecutor actionExecutor) {
        this.actionExecutor =
                Objects.requireNonNull(actionExecutor, "actionExecutor must not be null");
    }

    /// Adds an observer to receive plan events.
    ///
    /// @param observer the observer to add, not null
    public void addObserver(PlanObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        observers.add(observer);
    }

    /// Removes an observer.
    ///
    /// @param observer the observer to remove
    /// @return true if the observer was removed
    public boolean removeObserver(PlanObserver observer) {
        return observers.remove(observer);
    }

    /// Executes a plan with the given context.
    ///
    /// @param plan the plan to execute, not null
    /// @param context workflow variables for template resolution, not null
    /// @return execution result, never null
    public PlanResult execute(Plan plan, Map<String, Object> context) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(context, "context must not be null");

        notifyObservers(PlanCreated.now(plan));

        if (!plan.hasSteps()) {
            PlanResult result = PlanResult.completed(List.of());
            notifyObservers(PlanCompleted.success(plan.id(), "No steps to execute"));
            return result;
        }

        List<StepResult> results = new ArrayList<>();
        Instant planStart = Instant.now();
        Map<String, Object> currentContext = new java.util.HashMap<>(context);

        for (PlannedStep step : plan.steps()) {
            // Check duration constraint
            Duration elapsed = Duration.between(planStart, Instant.now());
            if (elapsed.compareTo(plan.constraints().maxDuration()) > 0) {
                PlanResult timeout = PlanResult.timeout(results, plan.constraints().maxDuration());
                notifyObservers(PlanCompleted.failure(plan.id(), timeout.error()));
                return timeout;
            }

            notifyObservers(StepStarted.now(plan.id(), step));

            try {
                StepResult stepResult = executeStep(step, currentContext);
                results.add(stepResult);
                notifyObservers(StepCompleted.now(plan.id(), stepResult));

                if (stepResult.isFailure()) {
                    PlanResult failure =
                            PlanResult.failed(results, step.index(), stepResult.error());
                    notifyObservers(PlanCompleted.failure(plan.id(), stepResult.error()));
                    return failure;
                }

                // Update context with step output for next steps
                if (stepResult.output() != null) {
                    currentContext = mergeStepOutput(currentContext, step, stepResult);
                }

            } catch (Exception e) {
                StepResult errorResult =
                        StepResult.failure(
                                step.index(),
                                step.toolName(),
                                e.getMessage(),
                                Duration.between(planStart, Instant.now()));
                results.add(errorResult);
                notifyObservers(StepCompleted.now(plan.id(), errorResult));

                PlanResult failure = PlanResult.failed(results, step.index(), e.getMessage());
                notifyObservers(PlanCompleted.failure(plan.id(), e.getMessage()));
                return failure;
            }
        }

        PlanResult success = PlanResult.completed(results);
        String output = success.output() != null ? success.output().toString() : "Plan completed";
        notifyObservers(PlanCompleted.success(plan.id(), output));
        return success;
    }

    /// Executes a single step using the action executor.
    private StepResult executeStep(PlannedStep step, Map<String, Object> context) {
        Instant start = Instant.now();

        // Create a Send action for the tool
        Action action = new Action.Send(step.toolName(), step.arguments());

        ActionResult result = actionExecutor.execute(action, context);

        Duration duration = Duration.between(start, Instant.now());

        if (result.success()) {
            return StepResult.success(step.index(), step.toolName(), result.output(), duration);
        } else {
            return StepResult.failure(step.index(), step.toolName(), result.message(), duration);
        }
    }

    /// Merges step output into context for subsequent steps.
    private Map<String, Object> mergeStepOutput(
            Map<String, Object> context, PlannedStep step, StepResult result) {
        Map<String, Object> updated = new java.util.HashMap<>(context);

        // Store output under tool name and step index keys
        updated.put("_step_" + step.index() + "_output", result.output());
        updated.put("_last_output", result.output());

        return updated;
    }

    /// Notifies all observers of an event.
    private void notifyObservers(PlanEvent event) {
        for (PlanObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                // Don't let observer failures break execution
            }
        }
    }
}
