package io.hensu.cli.tool;

import jakarta.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/// Collects the reasons a declared tool source produced no tools, for the operator to read.
///
/// A tool source can fail in ways that are nobody's fault but the operator's: `mcp.yaml` does not
/// parse, a declared server is not installed, a server needs containment the host cannot provide.
/// Each of those leaves an agent's declared tool absent, and the node then fails with a
/// declared-versus-available diff that names the tool and cannot name the cause.
///
/// The cause was already written — to a logger the CLI switches off. `quarkus.log.console.level`
/// ships `OFF`, because a workflow run is a rendered document rather than a log stream, so every
/// such warning was discarded before anything could read it. This class is the channel that
/// replaces it: the provider records a notice, and the run prints them where it prints the
/// capability gaps.
///
/// Notices belong to runs, not to the process. A daemon serves several runs, possibly at once, so
/// each run registers itself with {@link #beginRun} and takes its own notices with {@link #endRun}:
/// a run that starts never erases another's, and a run that ends reports only what was recorded
/// while it was in flight. Tool sources are shared by every run in the process, so a notice is
/// recorded for every run in flight when it is raised: a server that failed to start is missing for
/// all of them, whichever run's tool resolution noticed. A notice raised while no run is in flight
/// reaches the logger only.
///
/// Notices are bounded and deduplicated per run. A source reports its state again each time a run
/// reconciles it, so an overlapping run would otherwise see the same sentence twice; the bound
/// keeps a pathological catalog from growing a run's list without limit.
///
/// @see io.hensu.cli.ui.ToolSourceNoticeReport for the operator-facing rendering
/// @see io.hensu.core.tool.CapabilityGaps for the per-call half of the same question
@Singleton
public class ToolSourceNotices {

    /// Most notices retained per run. Beyond this the newest are dropped, because the first
    /// misconfiguration is the one an operator acts on.
    public static final int MAX_NOTICES = 32;

    private final Map<String, RunNotices> runs = new ConcurrentHashMap<>();

    /// Starts collecting notices for one run.
    ///
    /// @param executionId the run's execution id, not null
    public void beginRun(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        runs.put(executionId, new RunNotices());
    }

    /// Records one reason a tool source is not offering what it declared, for every run in flight.
    ///
    /// @param notice the operator-facing sentence, not null
    /// @apiNote **Side effects**: none beyond the collections. Never throws, and never fails the
    ///     run it is describing.
    public void record(String notice) {
        Objects.requireNonNull(notice, "notice must not be null");
        runs.values().forEach(run -> run.add(notice));
    }

    /// Stops collecting for one run and returns what it collected, oldest first.
    ///
    /// @param executionId the run's execution id, not null
    /// @return an immutable snapshot, never null (empty for a run that was never begun)
    public List<String> endRun(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        RunNotices run = runs.remove(executionId);
        return run == null ? List.of() : run.snapshot();
    }

    /// One run's notices, in the order first recorded.
    ///
    /// Guarded by a {@link ReentrantLock} rather than `synchronized`, because recording happens
    /// on the Virtual Threads that resolve tools.
    private static final class RunNotices {

        private final ReentrantLock lock = new ReentrantLock();
        private final Set<String> notices = new LinkedHashSet<>();

        void add(String notice) {
            lock.lock();
            try {
                if (notices.size() < MAX_NOTICES) {
                    notices.add(notice);
                }
            } finally {
                lock.unlock();
            }
        }

        List<String> snapshot() {
            lock.lock();
            try {
                return List.copyOf(notices);
            } finally {
                lock.unlock();
            }
        }
    }
}
