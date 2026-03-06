package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.ui.AnsiStyles;
import java.io.IOException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Cancels a running workflow execution in the daemon.
///
/// Sends a {@code cancel} frame to the daemon, which marks the execution as
/// CANCELLED and signals all attached clients. The execution virtual thread is
/// interrupted on its next blocking point.
///
/// ### Usage
/// ```
/// hensu cancel <execution-id>
/// ```
///
/// @see PsCommand for listing running execution IDs
/// @see AttachCommand for streaming output without cancelling
@Command(name = "cancel", description = "Cancel a running workflow execution")
public class CancelCommand extends HensuCommand {

    @Parameters(index = "0", description = "Execution ID to cancel (from hensu ps)")
    private String execId;

    @Override
    protected void execute() {
        AnsiStyles styles = AnsiStyles.of(true);

        if (!DaemonClient.isAlive()) {
            System.err.println(styles.error("No daemon is running."));
            return;
        }

        try {
            new DaemonClient().cancel(execId);
            System.out.println(
                    styles.checkmark() + " " + styles.bold("Cancelled execution: " + execId));
        } catch (IOException e) {
            System.err.println(styles.error("Failed to cancel execution: " + e.getMessage()));
        }
    }
}
