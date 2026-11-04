package io.hensu.server.execution;

import io.hensu.core.execution.RunPreemptedException;
import java.util.Objects;

/// This node's view of one live run: the claim it holds and whether that claim still
/// stands.
///
/// The fence on every write is what keeps a superseded run harmless; the handle only
/// lets the run stop sooner. A renewal that does not return the claim, or a drain that
/// releases it, marks the handle, and the run's next wait or check ends with
/// {@link RunPreemptedException} instead of waiting out a tool call or a model turn
/// whose result it could no longer record.
///
/// @implNote Thread-safe: the renewal job, the drain and the run's own threads – one per
///     concurrent branch – all read it.
public final class RunHandle {

    private final Claim claim;
    private volatile String preempted;

    RunHandle(Claim claim) {
        this.claim = Objects.requireNonNull(claim, "claim must not be null");
    }

    /// @return the claim this run holds, never null
    public Claim claim() {
        return claim;
    }

    /// Marks the claim as gone.
    ///
    /// @param reason why, for the run's log line, not null
    void preempt(String reason) {
        if (preempted == null) {
            preempted = reason;
        }
    }

    /// Stops the run if its claim is known to be gone.
    ///
    /// @throws RunPreemptedException if it is
    public void checkLive() {
        String reason = preempted;
        if (reason != null) {
            throw new RunPreemptedException("Run of " + claim + " preempted: " + reason);
        }
    }
}
