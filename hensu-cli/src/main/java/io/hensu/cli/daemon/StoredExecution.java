package io.hensu.cli.daemon;

import io.hensu.core.execution.result.ExecutionResult;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/// Runtime record for a single daemon-managed workflow execution.
///
/// Holds the current lifecycle state, a circular output buffer for replay on
/// re-attach, and the set of subscriber queues for live output delivery.
///
/// ### Contracts
/// - **Precondition**: {@code id} and {@code workflowId} must be non-null
/// - **Postcondition**: once a terminal state is reached, {@link #getStatus()} never changes
/// - **Invariant**: {@link #getOutputBuffer()} is never null
///
/// @implNote All status-mutation methods are {@code synchronized}. The
/// {@link OutputRingBuffer} and subscriber list have their own thread-safety.
///
/// @see ExecutionStore
/// @see OutputRingBuffer
public final class StoredExecution {

    private static final String POISON_PILL = "__POISON__";

    private final String id;
    private final String workflowId;
    private final Instant startedAt;
    private final OutputRingBuffer outputBuffer = new OutputRingBuffer();
    private final CopyOnWriteArrayList<BlockingQueue<String>> liveSubscribers =
            new CopyOnWriteArrayList<>();

    private volatile ExecutionStatus status = ExecutionStatus.QUEUED;
    private volatile String currentNode;
    private volatile Instant completedAt;
    private volatile ExecutionResult result;
    private volatile String failureMessage;

    /// Creates a new execution record in {@link ExecutionStatus#QUEUED} state.
    ///
    /// @param id         unique execution identifier, not null
    /// @param workflowId workflow name or id, not null
    public StoredExecution(String id, String workflowId) {
        this.id = id;
        this.workflowId = workflowId;
        this.startedAt = Instant.now();
    }

    // — State transitions ————————————————————————————————————————————————————

    /// Transitions status to {@link ExecutionStatus#RUNNING}.
    ///
    /// @apiNote **Side effects**: updates {@link #getStatus()} and {@link #getCurrentNode()}.
    /// @param firstNode the first node being executed, not null
    public synchronized void markRunning(String firstNode) {
        this.status = ExecutionStatus.RUNNING;
        this.currentNode = firstNode;
    }

    /// Transitions status to {@link ExecutionStatus#COMPLETED} and signals all subscribers.
    ///
    /// @apiNote **Side effects**: sets result, completedAt, broadcasts exec_end frame,
    /// poisons subscriber queues.
    /// @param result the final execution result, not null
    /// @param finalFrame serialized JSON exec_end frame to broadcast, not null
    public synchronized void markCompleted(ExecutionResult result, String finalFrame) {
        this.status = ExecutionStatus.COMPLETED;
        this.result = result;
        this.completedAt = Instant.now();
        broadcastAndPoison(finalFrame);
    }

    /// Transitions status to {@link ExecutionStatus#FAILED} and signals all subscribers.
    ///
    /// @param message failure description, not null
    /// @param errorFrame serialized JSON error frame to broadcast, not null
    public synchronized void markFailed(String message, String errorFrame) {
        this.status = ExecutionStatus.FAILED;
        this.failureMessage = message;
        this.completedAt = Instant.now();
        broadcastAndPoison(errorFrame);
    }

    /// Transitions status to {@link ExecutionStatus#CANCELLED} and signals all subscribers.
    ///
    /// @param cancelFrame serialized JSON exec_end frame to broadcast, not null
    public synchronized void markCancelled(String cancelFrame) {
        this.status = ExecutionStatus.CANCELLED;
        this.completedAt = Instant.now();
        broadcastAndPoison(cancelFrame);
    }

    /// Transitions status to {@link ExecutionStatus#AWAITING_REVIEW}.
    ///
    /// Called when the execution virtual thread blocks at a human-review checkpoint.
    /// The node identifier is retained so {@code hensu ps} can show which node is
    /// waiting for review.
    ///
    /// @param nodeId the node awaiting review, not null
    public synchronized void markAwaitingReview(String nodeId) {
        if (!status.isTerminal()) {
            this.status = ExecutionStatus.AWAITING_REVIEW;
            this.currentNode = nodeId;
        }
    }

    /// Transitions status back to {@link ExecutionStatus#RUNNING} after a review completes.
    ///
    /// @param nodeId the node now executing, not null
    public synchronized void markResumedAfterReview(String nodeId) {
        if (status == ExecutionStatus.AWAITING_REVIEW) {
            this.status = ExecutionStatus.RUNNING;
            this.currentNode = nodeId;
        }
    }

    /// Updates the execution status from a review lifecycle callback.
    ///
    /// Routes {@link ExecutionStatus#AWAITING_REVIEW} to {@link #markAwaitingReview} and
    /// {@link ExecutionStatus#RUNNING} to {@link #markResumedAfterReview} using the current
    /// node. Ignores terminal statuses — those have dedicated transition methods.
    ///
    /// @param newStatus the new status signaled by the review manager, not null
    public void updateStatus(ExecutionStatus newStatus) {
        switch (newStatus) {
            case AWAITING_REVIEW -> markAwaitingReview(currentNode);
            case RUNNING -> markResumedAfterReview(currentNode);
            default -> {}
        }
    }

    /// Updates the currently-executing node identifier.
    ///
    /// @param nodeId current node id, not null
    public void setCurrentNode(String nodeId) {
        this.currentNode = nodeId;
    }

    // — Subscriber management ————————————————————————————————————————————————

    /// Adds a subscriber queue to receive live output frames.
    ///
    /// @param queue the queue to add, not null
    public void addSubscriber(BlockingQueue<String> queue) {
        liveSubscribers.add(queue);
    }

    /// Removes a subscriber queue, stopping live output delivery.
    ///
    /// @param queue the queue to remove, not null
    public void removeSubscriber(BlockingQueue<String> queue) {
        liveSubscribers.remove(queue);
    }

    /// Broadcasts a serialized JSON frame to all current live subscribers.
    ///
    /// Uses {@link BlockingQueue#offer(Object)} — frames are silently dropped for
    /// subscribers whose queue is full (capacity 200). Slow subscribers that
    /// consistently fall behind are evicted; they will receive missed output via
    /// ring-buffer replay on re-attach.
    ///
    /// @param jsonFrame serialized JSON frame line, not null
    public void broadcast(String jsonFrame) {
        liveSubscribers.removeIf(queue -> !queue.offer(jsonFrame));
    }

    // — Accessors ————————————————————————————————————————————————————————————

    /// Returns the unique execution identifier.
    ///
    /// @return execution id, never null
    public String getId() {
        return id;
    }

    /// Returns the workflow name or id.
    ///
    /// @return workflow id, never null
    public String getWorkflowId() {
        return workflowId;
    }

    /// Returns the current lifecycle status.
    ///
    /// @return current status, never null
    public ExecutionStatus getStatus() {
        return status;
    }

    /// Returns the node currently being executed, if any.
    ///
    /// @return current node id, may be null (before first node or after completion)
    public String getCurrentNode() {
        return currentNode;
    }

    /// Returns the instant this execution was created.
    ///
    /// @return start time, never null
    public Instant getStartedAt() {
        return startedAt;
    }

    /// Returns the instant this execution reached a terminal state, if it has.
    ///
    /// @return completion time, may be null if still running
    public Instant getCompletedAt() {
        return completedAt;
    }

    /// Returns the final execution result for completed executions.
    ///
    /// @return execution result, may be null if not yet completed
    public ExecutionResult getResult() {
        return result;
    }

    /// Returns the failure message for failed executions.
    ///
    /// @return failure message, may be null if not failed
    public String getFailureMessage() {
        return failureMessage;
    }

    /// Returns the circular output buffer for replay on re-attach.
    ///
    /// @return output buffer, never null
    public OutputRingBuffer getOutputBuffer() {
        return outputBuffer;
    }

    /// Returns the elapsed time in milliseconds since this execution was created.
    ///
    /// @return elapsed milliseconds, always {@code >= 0}
    public long getElapsedMs() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    // — Internal ——————————————————————————————————————————————————————————————

    private void broadcastAndPoison(String finalFrame) {
        for (BlockingQueue<String> queue : liveSubscribers) {
            queue.offer(
                    finalFrame); // best-effort; ring buffer preserves output for re-attach replay
            if (!queue.offer(POISON_PILL)) {
                // Queue still full after final frame — subscriber is unresponsive; evict it.
                liveSubscribers.remove(queue);
            }
        }
    }

    /// Returns the poison-pill sentinel used to signal queue drain loops.
    ///
    /// @return sentinel string, never null
    public static String poisonPill() {
        return POISON_PILL;
    }
}
