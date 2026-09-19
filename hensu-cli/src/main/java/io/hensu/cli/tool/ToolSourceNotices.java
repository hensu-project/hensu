package io.hensu.cli.tool;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

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
/// Notices are bounded. A misconfiguration repeats per run, not per call, so the bound exists only
/// to keep a pathological catalog from growing the list without limit.
///
/// @see io.hensu.cli.ui.ToolSourceNoticeReport for the operator-facing rendering
/// @see io.hensu.core.tool.CapabilityGaps for the per-call half of the same question
@Singleton
public class ToolSourceNotices {

    /// Most notices retained. Beyond this the newest are dropped, because the first
    /// misconfiguration is the one an operator acts on.
    public static final int MAX_NOTICES = 32;

    private final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<>();

    /// Records one reason a tool source is not offering what it declared.
    ///
    /// @param notice the operator-facing sentence, not null
    /// @apiNote **Side effects**: none beyond the collection. Never throws, and never fails the
    ///     run it is describing.
    public void record(String notice) {
        Objects.requireNonNull(notice, "notice must not be null");
        if (notices.size() < MAX_NOTICES) {
            notices.add(notice);
        }
    }

    /// Returns the notices recorded so far, oldest first.
    ///
    /// @return an immutable snapshot, never null
    public List<String> all() {
        return List.copyOf(new ArrayList<>(notices));
    }

    /// Forgets every notice, so a daemon serving many runs does not report one run's
    /// misconfiguration on the next.
    public void clear() {
        notices.clear();
    }
}
