package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.cli.ui.AnsiStyles;
import java.io.IOException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Re-attaches to a running or completed workflow execution in the daemon.
///
/// Replays buffered output from the ring buffer, then streams live output until
/// the execution completes or the client detaches with Ctrl+C.
///
/// ### Usage
/// ```
/// hensu attach <execution-id>
/// ```
///
/// ### Ctrl+C behavior
/// Pressing Ctrl+C detaches the client — the execution keeps running in the daemon.
/// The execution ID is printed so you can re-attach at any time with {@code hensu attach}.
///
/// @see PsCommand for listing execution IDs
/// @see CancelCommand for stopping an execution
/// @see DaemonClient#attach(String, java.util.function.Consumer)
@Command(name = "attach", description = "Attach to a running workflow execution")
public class AttachCommand extends HensuCommand {

    @Parameters(index = "0", description = "Execution ID (from hensu ps)")
    private String execId;

    @Override
    protected void execute() {
        AnsiStyles styles = AnsiStyles.of(true);

        if (!DaemonClient.isAlive()) {
            System.err.println(styles.error("No daemon is running."));
            return;
        }

        // Install SIGINT handler — Ctrl+C detaches, not kills
        String[] currentExecId = {execId};
        boolean[] completed = {false};
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (completed[0]) return;
                                    new DaemonClient().detach(currentExecId[0]);
                                    System.err.printf(
                                            "%nDetached. Execution %s is still running.%n",
                                            currentExecId[0]);
                                    System.err.printf(
                                            "Re-attach:  hensu attach %s%n", currentExecId[0]);
                                    System.err.printf(
                                            "Cancel:     hensu cancel %s%n", currentExecId[0]);
                                }));

        try {
            boolean[] replaying = {false};

            new DaemonClient()
                    .attach(execId, frame -> handleFrame(frame, styles, replaying, completed));

        } catch (IOException e) {
            System.err.println(styles.error("Connection error: " + e.getMessage()));
        }
    }

    private void handleFrame(
            DaemonFrame frame, AnsiStyles styles, boolean[] replaying, boolean[] completed) {
        switch (frame.type) {
            case "replay_start" -> {
                replaying[0] = true;
                if (Boolean.TRUE.equals(frame.truncated)) {
                    System.out.println(
                            styles.warn("[Replay truncated — " + frame.bytesLost + " bytes lost]"));
                } else {
                    System.out.println(styles.gray("[Replaying buffered output...]"));
                }
            }
            case "replay_end" -> {
                replaying[0] = false;
                System.out.println(styles.gray("[Live output follows]"));
            }
            case "out" -> DaemonClient.printOutFrame(frame);
            case "exec_end" -> {
                completed[0] = true;
                System.out.println();
                boolean ok =
                        "SUCCESS".equals(frame.status)
                                || (frame.status != null && frame.status.startsWith("SUCCESS"));
                if (ok) {
                    System.out.printf(
                            "%s %s%n",
                            styles.checkmark(),
                            styles.bold("Execution completed: " + frame.status));
                } else {
                    System.out.printf(
                            "%s %s%n",
                            styles.crossmark(), styles.bold("Execution ended: " + frame.status));
                }
            }
            case "error" ->
                    System.err.printf(
                            "%s %s%n",
                            styles.crossmark(),
                            styles.error(frame.message != null ? frame.message : "Unknown error"));
            default -> {
                /* ignore control frames */
            }
        }
    }
}
