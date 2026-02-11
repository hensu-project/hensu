package io.hensu.server.integration;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/// Configurable {@link ReviewHandler} for integration tests.
///
/// Injected into {@link io.hensu.server.config.HensuEnvironmentProducer} as a CDI
/// `@Alternative`, replacing the default `AUTO_APPROVE` behavior. Tests script
/// review decisions by enqueuing them before workflow execution:
///
/// {@snippet :
/// testReviewHandler.enqueueDecision(new ReviewDecision.Approve());
/// testReviewHandler.enqueueDecision(new ReviewDecision.Backtrack("nodeA", null, "retry"));
/// pushAndExecute(workflow, context);
/// }
///
/// When the queue is empty, falls back to a configurable default decision
/// (approve by default).
///
/// ### Contracts
/// - **Precondition**: `reset()` must be called between tests (handled by
///   {@link IntegrationTestBase})
/// - **Postcondition**: returns enqueued decisions in FIFO order
/// - **Invariant**: thread-safe via {@link ConcurrentLinkedQueue}
///
/// @implNote Thread-safe. Uses a lock-free queue for decision storage.
///
/// @see ReviewDecision for the sealed decision hierarchy
/// @see ReviewConfig for review trigger configuration
@Alternative
@Priority(1)
@ApplicationScoped
public class TestReviewHandler implements ReviewHandler {

    private final Queue<ReviewDecision> decisions = new ConcurrentLinkedQueue<>();
    private volatile ReviewDecision defaultDecision = new ReviewDecision.Approve();

    /// Enqueues a review decision to be returned on the next review request.
    ///
    /// @apiNote **Side effects**: modifies the internal decision queue
    ///
    /// @param decision the decision to enqueue, not null
    public void enqueueDecision(ReviewDecision decision) {
        decisions.add(decision);
    }

    /// Sets the fallback decision returned when the queue is empty.
    ///
    /// @param decision the default decision, not null
    public void setDefaultDecision(ReviewDecision decision) {
        this.defaultDecision = decision;
    }

    /// Resets the handler to its initial state (empty queue, approve default).
    ///
    /// @apiNote **Side effects**: clears all enqueued decisions
    public void reset() {
        decisions.clear();
        defaultDecision = new ReviewDecision.Approve();
    }

    /// Returns the next enqueued decision, or the default if the queue is empty.
    ///
    /// @param node the executed node requiring review, not null
    /// @param result the execution result to review, not null
    /// @param state current workflow state, not null
    /// @param history execution history for backtrack selection, not null
    /// @param config review behavior settings, not null
    /// @param workflow the workflow definition, not null
    /// @return the next scripted decision or the default, never null
    @Override
    public ReviewDecision requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        ReviewDecision next = decisions.poll();
        return next != null ? next : defaultDecision;
    }
}
