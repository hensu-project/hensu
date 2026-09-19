package io.hensu.cli.ui;

import java.io.PrintStream;
import java.util.List;

/// Renders the reasons a declared tool source offered no tools.
///
/// Prints nothing when there is nothing to say. An empty section on every healthy run would train
/// the operator to skip the block, which is the one thing this section cannot afford: it appears
/// exactly when a run did less than the workflow asked for.
///
/// @see io.hensu.cli.tool.ToolSourceNotices for where the lines come from
public final class ToolSourceNoticeReport {

    private ToolSourceNoticeReport() {}

    /// Prints the tool-source section, or nothing when no source reported a problem.
    ///
    /// @param out where to print, not null
    /// @param styles the run's colour settings, not null
    /// @param notices the recorded notices, may be null or empty
    /// @apiNote **Side effects**: writes to `out`.
    public static void print(PrintStream out, AnsiStyles styles, List<String> notices) {
        if (notices == null || notices.isEmpty()) {
            return;
        }

        out.printf("%n%s%n", styles.bold("  Tool sources:"));
        for (String notice : notices) {
            out.printf("  %s %s%n", styles.warn("!"), styles.gray(notice));
        }
    }
}
