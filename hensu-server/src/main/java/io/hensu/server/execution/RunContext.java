package io.hensu.server.execution;

import io.hensu.core.execution.RunPreemptedException;
import java.util.Objects;
import java.util.concurrent.Callable;

/// The run the calling thread belongs to.
///
/// Bound for the whole of a run by {@link io.hensu.server.workflow.ExecutionRunner}. A
/// `ScopedValue`, so the concurrent branches of a fork or parallel node – which run in a
/// `StructuredTaskScope` – inherit it, and a tool provider deep in the engine can fence
/// its writes on the run's claim without the engine knowing claims exist.
public final class RunContext {

    private static final ScopedValue<RunHandle> CURRENT = ScopedValue.newInstance();

    private RunContext() {}

    /// Returns the calling thread's run.
    ///
    /// @return the run, never null
    /// @throws RunPreemptedException if no run is bound – a tool call outside a run has no
    ///     claim to record its intent under
    public static RunHandle current() {
        if (!CURRENT.isBound()) {
            throw new RunPreemptedException("No run is bound to this thread");
        }
        return CURRENT.get();
    }

    /// Runs `task` with `run` bound as the calling thread's run.
    ///
    /// @param run the run, not null
    /// @param task the work, not null
    /// @return the task's result
    /// @throws Exception whatever the task throws
    public static <T> T runAs(RunHandle run, Callable<T> task) throws Exception {
        Objects.requireNonNull(run, "run must not be null");
        return ScopedValue.where(CURRENT, run).call(task::call);
    }
}
