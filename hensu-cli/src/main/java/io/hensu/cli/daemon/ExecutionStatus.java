package io.hensu.cli.daemon;

/// Lifecycle state of a daemon-managed workflow execution.
///
/// ```
/// QUEUED ————> RUNNING ————> COMPLETED
///                  │————————> AWAITING_REVIEW ————> RUNNING (loop)
///                  │————————> FAILED
///                  │————————> CANCELLED
///                  │————————> TIMED_OUT
/// ```
public enum ExecutionStatus {

    /// Accepted by the daemon but waiting for a virtual thread slot.
    QUEUED,

    /// Currently executing in a virtual thread.
    RUNNING,

    /// Execution is paused at a human-review checkpoint.
    ///
    /// The execution virtual thread is blocked waiting for a {@code review_response}
    /// frame. Use {@code hensu attach <id>} to reconnect and complete the review.
    AWAITING_REVIEW,

    /// Finished successfully — result available in {@link StoredExecution}.
    COMPLETED,

    /// Terminated due to an uncaught exception — failure message available.
    FAILED,

    /// Cancelled by explicit client {@code cancel} request.
    CANCELLED,

    /// Interrupted after exceeding the configured node timeout.
    TIMED_OUT;

    /// Returns {@code true} if no further state transitions are possible.
    ///
    /// @return {@code true} for COMPLETED, FAILED, CANCELLED, TIMED_OUT; {@code false}
    /// for QUEUED, RUNNING, and AWAITING_REVIEW
    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING && this != AWAITING_REVIEW;
    }
}
