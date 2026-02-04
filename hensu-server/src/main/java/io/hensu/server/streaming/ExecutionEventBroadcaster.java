package io.hensu.server.streaming;

import io.hensu.core.plan.PlanEvent;
import io.hensu.core.plan.PlanObserver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/// Broadcasts execution events to SSE subscribers.
///
/// Manages per-execution event streams using Mutiny's BroadcastProcessor.
/// Implements {@link PlanObserver} to receive plan events and convert them
/// to SSE-friendly {@link ExecutionEvent} DTOs.
///
/// ### Thread Safety
/// Thread-safe. Uses ConcurrentHashMap for subscription management and
/// BroadcastProcessor for thread-safe event delivery.
///
/// ### Memory Management
/// Subscriptions are automatically cleaned up when:
/// - Execution completes (success or failure)
/// - Client disconnects (detected via Multi cancellation)
/// - Explicit unsubscribe via {@link #unsubscribe(String)}
///
/// ### Usage
/// {@snippet :
/// // Subscribe to execution events (in SSE endpoint)
/// Multi<ExecutionEvent> events = broadcaster.subscribe(executionId);
///
/// // Publish events (called by AgenticNodeExecutor via PlanObserver)
/// broadcaster.setCurrentExecution(executionId);
/// broadcaster.onEvent(planEvent);  // Automatically converted and broadcast
///
/// // Or publish directly
/// broadcaster.publish(executionId, ExecutionEvent.ExecutionStarted.now(...));
/// }
///
/// @see io.hensu.server.api.ExecutionEventResource for SSE endpoint
/// @see ExecutionEvent for event types
@ApplicationScoped
public class ExecutionEventBroadcaster implements PlanObserver {

    private static final Logger LOG = Logger.getLogger(ExecutionEventBroadcaster.class);

    /// Maps execution ID to broadcast processor.
    private final Map<String, BroadcastProcessor<ExecutionEvent>> processors =
            new ConcurrentHashMap<>();

    /// Maps plan ID to execution ID for event routing.
    private final Map<String, String> planToExecution = new ConcurrentHashMap<>();

    /// Thread-local current execution ID for PlanObserver context.
    private static final ThreadLocal<String> CURRENT_EXECUTION = new ThreadLocal<>();

    /// Subscribes to events for an execution.
    ///
    /// @param executionId the execution to subscribe to
    /// @return event stream that emits events for this execution
    public Multi<ExecutionEvent> subscribe(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");

        BroadcastProcessor<ExecutionEvent> processor =
                processors.computeIfAbsent(
                        executionId,
                        id -> {
                            LOG.debugv("Creating broadcast processor for execution: {0}", id);
                            return BroadcastProcessor.create();
                        });

        return processor
                .onCancellation()
                .invoke(() -> LOG.debugv("Client disconnected from execution: {0}", executionId));
    }

    /// Publishes an event to all subscribers of an execution.
    ///
    /// @param executionId the target execution
    /// @param event the event to publish
    public void publish(String executionId, ExecutionEvent event) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(event, "event must not be null");

        BroadcastProcessor<ExecutionEvent> processor = processors.get(executionId);
        if (processor != null) {
            LOG.debugv("Publishing {0} to execution {1}", event.type(), executionId);
            processor.onNext(event);
        } else {
            LOG.tracev(
                    "No subscribers for execution {0}, event {1} dropped",
                    executionId, event.type());
        }
    }

    /// Sets the current execution context for PlanObserver events.
    ///
    /// Call this before executing a plan to route plan events to the correct execution.
    ///
    /// @param executionId the execution ID, or null to clear
    public void setCurrentExecution(String executionId) {
        if (executionId != null) {
            CURRENT_EXECUTION.set(executionId);
        } else {
            CURRENT_EXECUTION.remove();
        }
    }

    /// Gets the current execution ID from thread-local context.
    ///
    /// @return current execution ID, or null if not set
    public String getCurrentExecution() {
        return CURRENT_EXECUTION.get();
    }

    /// Associates a plan ID with an execution ID for event routing.
    ///
    /// @param planId the plan identifier
    /// @param executionId the execution identifier
    public void registerPlan(String planId, String executionId) {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        planToExecution.put(planId, executionId);
    }

    /// Completes the event stream for an execution.
    ///
    /// Should be called when execution finishes to clean up resources.
    ///
    /// @param executionId the execution to complete
    public void complete(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");

        BroadcastProcessor<ExecutionEvent> processor = processors.remove(executionId);
        if (processor != null) {
            LOG.debugv("Completing broadcast for execution: {0}", executionId);
            processor.onComplete();
        }

        // Clean up plan mappings
        planToExecution.entrySet().removeIf(e -> executionId.equals(e.getValue()));
    }

    /// Completes with error for an execution.
    ///
    /// @param executionId the execution that errored
    /// @param error the error
    public void error(String executionId, Throwable error) {
        Objects.requireNonNull(executionId, "executionId must not be null");

        BroadcastProcessor<ExecutionEvent> processor = processors.remove(executionId);
        if (processor != null) {
            LOG.debugv("Completing broadcast with error for execution: {0}", executionId);
            processor.onError(error);
        }

        planToExecution.entrySet().removeIf(e -> executionId.equals(e.getValue()));
    }

    /// Unsubscribes and cleans up an execution stream.
    ///
    /// @param executionId the execution to unsubscribe
    public void unsubscribe(String executionId) {
        complete(executionId);
    }

    /// Returns the number of active subscriptions.
    ///
    /// @return count of active execution streams
    public int activeSubscriptionCount() {
        return processors.size();
    }

    /// Returns whether an execution has active subscribers.
    ///
    /// @param executionId the execution to check
    /// @return true if there are subscribers
    public boolean hasSubscribers(String executionId) {
        return processors.containsKey(executionId);
    }

    // --- PlanObserver implementation ---

    @Override
    public void onEvent(PlanEvent event) {
        String executionId = resolveExecutionId(event.planId());
        if (executionId == null) {
            LOG.tracev(
                    "No execution context for plan event: {0}", event.getClass().getSimpleName());
            return;
        }

        ExecutionEvent sseEvent = convertEvent(executionId, event);
        if (sseEvent != null) {
            publish(executionId, sseEvent);
        }
    }

    /// Resolves execution ID from plan ID or thread-local context.
    private String resolveExecutionId(String planId) {
        // First check plan mapping
        String executionId = planToExecution.get(planId);
        if (executionId != null) {
            return executionId;
        }

        // Fall back to thread-local context
        return CURRENT_EXECUTION.get();
    }

    /// Converts a PlanEvent to an SSE ExecutionEvent.
    private ExecutionEvent convertEvent(String executionId, PlanEvent event) {
        return switch (event) {
            case PlanEvent.PlanCreated e -> {
                registerPlan(e.planId(), executionId);
                yield ExecutionEvent.PlanCreated.from(executionId, e);
            }
            case PlanEvent.StepStarted e -> ExecutionEvent.StepStarted.from(executionId, e);
            case PlanEvent.StepCompleted e -> ExecutionEvent.StepCompleted.from(executionId, e);
            case PlanEvent.PlanRevised e -> ExecutionEvent.PlanRevised.from(executionId, e);
            case PlanEvent.PlanCompleted e -> ExecutionEvent.PlanCompleted.from(executionId, e);
            case PlanEvent.PlanPaused e ->
                    ExecutionEvent.ExecutionPaused.now(executionId, null, e.planId(), e.reason());
        };
    }
}
