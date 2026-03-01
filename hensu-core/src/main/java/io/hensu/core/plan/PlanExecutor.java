package io.hensu.core.plan;

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

/// Executes plans step by step by dispatching each {@link PlannedStep} to its
/// registered {@link StepHandler} via a {@link StepHandlerRegistry}.
///
/// Events are emitted to registered {@link PlanObserver}s so external components
/// (e.g. SSE broadcasters) can stream progress in real time.
///
/// ### Execution Flow
/// 1. Emit {@link PlanEvent.PlanCreated} event
/// 2. For each step in order:
///    - Check elapsed time against {@link PlanConstraints#maxDuration()}
///    - Emit {@link PlanEvent.StepStarted}
///    - Delegate to {@link StepHandlerRegistry#dispatch(PlannedStep, Map)}
///    - Emit {@link PlanEvent.StepCompleted}
///    - On failure: return failed result immediately (no automatic retry here)
///    - On success: merge step output into context for subsequent steps
///
/// {@link PlanEvent.PlanCompleted} is intentionally **not** emitted here.
/// The calling {@link io.hensu.core.execution.executor.PlanExecutionProcessor}
/// owns the terminal event because it has the full picture — replanning
/// attempts, revision failures, and max-replan exhaustion all happen above
/// this class.
///
/// ### Retry / Revision
/// Retry and plan revision are the responsibility of the caller
/// ({@link io.hensu.core.execution.executor.AgenticNodeExecutor}), not this class.
///
/// @implNote Thread-safe for observer registration ({@link CopyOnWriteArrayList}).
/// A single {@code PlanExecutor} instance must not be shared across concurrent
/// plan executions — use one per execution or protect externally.
///
/// @see Plan for plan structure
/// @see StepHandlerRegistry for handler dispatch
/// @see PlanResult for the aggregated outcome
/// @see PlanObserver for event subscription
public class PlanExecutor {

    private final StepHandlerRegistry stepHandlerRegistry;
    private final List<PlanObserver> observers = new CopyOnWriteArrayList<>();

    /// Creates an executor backed by the given step-handler registry.
    ///
    /// @param stepHandlerRegistry registry that dispatches each step, not null
    /// @throws NullPointerException if stepHandlerRegistry is null
    public PlanExecutor(StepHandlerRegistry stepHandlerRegistry) {
        this.stepHandlerRegistry =
                Objects.requireNonNull(stepHandlerRegistry, "stepHandlerRegistry must not be null");
    }

    /// Registers an observer to receive plan lifecycle events.
    ///
    /// @apiNote **Side effects**: adds to the internal observer list; duplicates are allowed.
    ///
    /// @param observer the observer to add, not null
    public void addObserver(PlanObserver observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        observers.add(observer);
    }

    /// Removes a previously registered observer.
    ///
    /// @param observer the observer to remove
    /// @return {@code true} if the observer was present and removed
    public boolean removeObserver(PlanObserver observer) {
        return observers.remove(observer);
    }

    /// Executes all steps of the plan in order.
    ///
    /// @param plan    the plan to execute, not null
    /// @param context mutable workflow variables available to step handlers, not null
    /// @return aggregated execution result, never null
    public PlanResult execute(Plan plan, Map<String, Object> context) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(context, "context must not be null");

        notifyObservers(PlanCreated.now(plan));

        if (!plan.hasSteps()) {
            return PlanResult.completed(List.of());
        }

        List<StepResult> results = new ArrayList<>();
        Instant planStart = Instant.now();
        Map<String, Object> currentContext = new java.util.HashMap<>(context);

        for (PlannedStep step : plan.steps()) {
            Duration elapsed = Duration.between(planStart, Instant.now());
            if (elapsed.compareTo(plan.constraints().maxDuration()) > 0) {
                return PlanResult.timeout(results, plan.constraints().maxDuration());
            }

            notifyObservers(StepStarted.now(plan.id(), step));

            try {
                StepResult stepResult = stepHandlerRegistry.dispatch(step, currentContext);
                results.add(stepResult);
                notifyObservers(StepCompleted.now(plan.id(), stepResult));

                if (stepResult.isFailure()) {
                    return PlanResult.failed(results, step.index(), stepResult.error());
                }

                if (stepResult.output() != null) {
                    currentContext = mergeStepOutput(currentContext, step, stepResult);
                }

            } catch (Exception e) {
                String msg = e.getMessage();
                StepResult errorResult =
                        StepResult.failure(
                                step.index(),
                                stepLabel(step),
                                msg,
                                Duration.between(planStart, Instant.now()));
                results.add(errorResult);
                notifyObservers(StepCompleted.now(plan.id(), errorResult));
                return PlanResult.failed(results, step.index(), msg);
            }
        }

        return PlanResult.completed(results);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> mergeStepOutput(
            Map<String, Object> context, PlannedStep step, StepResult result) {
        Map<String, Object> updated = new java.util.HashMap<>(context);
        updated.put("_step_" + step.index() + "_output", result.output());
        updated.put("_last_output", result.output());
        return updated;
    }

    private static String stepLabel(PlannedStep step) {
        return switch (step.action()) {
            case PlanStepAction.ToolCall tc -> tc.toolName();
            case PlanStepAction.Synthesize ignored -> "synthesize";
        };
    }

    private void notifyObservers(PlanEvent event) {
        for (PlanObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                // Observer failures must not break plan execution
            }
        }
    }
}
