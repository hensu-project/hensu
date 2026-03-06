package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.cli.ui.AnsiStyles;
import java.io.IOException;
import java.util.List;
import picocli.CommandLine.Command;

/// Lists workflow executions tracked by the running daemon.
///
/// Displays execution ID, workflow name, status, current node, and elapsed time —
/// analogous to {@code docker ps} for local workflow processes.
///
/// ### Usage
/// ```
/// hensu ps
/// ```
///
/// @see DaemonClient#list()
/// @see AttachCommand
/// @see CancelCommand
@Command(name = "ps", description = "List workflow executions tracked by the daemon")
public class PsCommand extends HensuCommand {

    @Override
    protected void execute() {
        AnsiStyles styles = AnsiStyles.of(true);

        if (!DaemonClient.isAlive()) {
            System.out.println(
                    styles.gray("No daemon is running. Start it with: hensu daemon start"));
            return;
        }

        try {
            List<DaemonFrame.PsEntry> executions = new DaemonClient().list();

            if (executions.isEmpty()) {
                System.out.println(styles.gray("No executions tracked."));
                return;
            }

            // Header
            System.out.printf(
                    "%-8s  %-36s  %-24s  %-12s  %-16s  %s%n",
                    "", "ID", "WORKFLOW", "STATUS", "NODE", "ELAPSED");
            System.out.println(styles.gray("─".repeat(110)));

            for (DaemonFrame.PsEntry e : executions) {
                String dot = styles.statusDot(e.status());
                String elapsed = formatElapsed(e.elapsedMs());
                String node = e.currentNode() != null ? e.currentNode() : "—";
                String status = colorStatus(styles, e.status());

                System.out.printf(
                        "%s  %-36s  %-24s  %-12s  %-16s  %s%n",
                        dot,
                        e.execId(),
                        truncate(e.workflowId(), 24),
                        status,
                        truncate(node, 16),
                        elapsed);
            }

        } catch (IOException e) {
            System.err.println(styles.error("Failed to query daemon: " + e.getMessage()));
        }
    }

    private String colorStatus(AnsiStyles styles, String status) {
        return switch (status) {
            case "RUNNING" -> styles.running(status);
            case "COMPLETED", "SUCCESS" -> styles.success(status);
            case "FAILED", "FAILURE" -> styles.error(status);
            case "CANCELLED" -> styles.cancelled(status);
            case "TIMED_OUT" -> styles.warn(status);
            default -> styles.gray(status);
        };
    }

    private String formatElapsed(long ms) {
        if (ms < 1_000) return ms + "ms";
        if (ms < 60_000) return (ms / 1_000) + "s";
        long mins = ms / 60_000;
        long secs = (ms % 60_000) / 1_000;
        return mins + "m " + secs + "s";
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
