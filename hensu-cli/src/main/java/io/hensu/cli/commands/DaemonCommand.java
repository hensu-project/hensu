package io.hensu.cli.commands;

import io.hensu.cli.daemon.DaemonClient;
import io.hensu.cli.daemon.DaemonPaths;
import io.hensu.cli.daemon.DaemonServer;
import io.hensu.cli.daemon.SystemBinaries;
import io.hensu.cli.ui.AnsiStyles;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Manages the Hensu background daemon process.
///
/// The daemon keeps the JVM and Kotlin compiler warm between workflow runs,
/// dramatically reducing execution startup time. It acts as a local engine —
/// the same role {@code hensu-server} plays for remote deployments.
///
/// ### Subcommands
/// ```
/// hensu daemon start    — Start the daemon in the background
/// hensu daemon stop     — Stop a running daemon gracefully
/// hensu daemon status   — Show daemon health and active execution count
/// ```
///
/// ### Service manager integration
/// {@code start} and {@code stop} delegate to the platform service manager when
/// its unit/agent is installed, so the service lifecycle stays consistent:
///
/// - **Linux (systemd):** delegates to {@code systemctl --user} when
///   {@code ~/.config/systemd/user/hensu-daemon.service} is present.
/// - **macOS (launchd):** delegates to {@code launchctl} when
///   {@code ~/Library/LaunchAgents/io.hensu.daemon.plist} is present.
///
/// When no service unit is found the commands fall back to spawning / stopping
/// a plain background child process directly.
///
/// @see DaemonServer
/// @see DaemonClient
@Command(
        name = "daemon",
        description = "Manage the Hensu background daemon",
        subcommands = {
            DaemonCommand.Start.class,
            DaemonCommand.Stop.class,
            DaemonCommand.Status.class
        })
public class DaemonCommand extends HensuCommand {

    @Override
    protected void execute() {
        // No-op: picocli prints usage when no subcommand given
    }

    // — start ——————————————————————————————————————————————————————————————

    /// Starts the Hensu daemon.
    ///
    /// Priority order:
    /// 1. If {@code --foreground} — block in the accept loop (used by systemd ExecStart
    ///    and the background-child launcher).
    /// 2. If the systemd user service unit is installed — delegate to
    ///    {@code systemctl --user start hensu-daemon}.
    /// 3. Otherwise — spawn a detached background child process.
    ///
    /// After launching, the command waits up to 5 s for the Unix socket to appear
    /// before returning to the caller.
    @Command(name = "start", description = "Start the Hensu daemon")
    static class Start extends HensuCommand {

        @Option(
                names = {"--foreground"},
                description = "Run in the foreground (used by background launcher)",
                hidden = true)
        private boolean foreground = false;

        @Inject DaemonServer daemonServer;

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);

            if (foreground) {
                // We ARE the daemon process — block in accept loop
                try {
                    daemonServer.start();
                } catch (IOException e) {
                    System.err.println(styles.error("Daemon failed to start: " + e.getMessage()));
                }
                return;
            }

            if (DaemonClient.isAlive()) {
                System.out.println(styles.success("Daemon is already running."));
                return;
            }

            try {
                if (isSystemdServiceInstalled()) {
                    startViaSystemd(styles);
                } else if (isLaunchdAgentInstalled()) {
                    startViaLaunchd(styles);
                } else {
                    startBackgroundChild(styles);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println(styles.error("Failed to launch daemon: " + e.getMessage()));
            }
        }

        private void startViaSystemd(AnsiStyles styles) throws IOException, InterruptedException {
            // With Type=notify, `systemctl start` blocks until sd_notify READY=1 is received.
            // No additional readiness poll needed.
            int exitCode =
                    new ProcessBuilder(
                                    SystemBinaries.systemctl(), "--user", "start", "hensu-daemon")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start()
                            .waitFor();
            if (exitCode != 0) {
                System.err.println(styles.error("systemctl start failed (exit " + exitCode + ")."));
                return;
            }
            System.out.println(styles.checkmark() + " " + styles.bold("Daemon started."));
            System.out.println(styles.gray("  Socket: " + DaemonPaths.socket()));
        }

        private void startViaLaunchd(AnsiStyles styles) throws IOException, InterruptedException {
            // launchctl start is non-blocking — poll the socket for readiness.
            int exitCode =
                    new ProcessBuilder(SystemBinaries.launchctl(), "start", "io.hensu.daemon")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start()
                            .waitFor();
            if (exitCode != 0) {
                System.err.println(styles.error("launchctl start failed (exit " + exitCode + ")."));
                return;
            }
            waitForSocket(styles);
        }

        private void startBackgroundChild(AnsiStyles styles)
                throws IOException, InterruptedException {
            String binary =
                    ProcessHandle.current()
                            .info()
                            .command()
                            .orElseThrow(() -> new IOException("Cannot determine binary path"));

            // When launched via `java -jar` the binary is the JVM itself, not the wrapper.
            // HENSU_JAR is exported by the installation wrapper so we can reconstruct the
            // full java -jar command. When running as a native binary HENSU_JAR is absent
            // and the binary path alone is sufficient.
            String hensuJar = System.getenv("HENSU_JAR");
            ProcessBuilder pb =
                    (hensuJar != null && !hensuJar.isBlank())
                            ? new ProcessBuilder(
                                    binary,
                                    "--enable-native-access=ALL-UNNAMED",
                                    "--sun-misc-unsafe-memory-access=allow",
                                    "--enable-preview",
                                    "-jar",
                                    hensuJar,
                                    "daemon",
                                    "start",
                                    "--foreground")
                            : new ProcessBuilder(binary, "daemon", "start", "--foreground");

            pb.redirectOutput(DaemonPaths.logFile().toFile())
                    .redirectError(DaemonPaths.logFile().toFile())
                    .start();

            waitForSocket(styles);
        }

        private void waitForSocket(AnsiStyles styles) throws IOException, InterruptedException {
            Files.createDirectories(DaemonPaths.base());
            long deadline = System.currentTimeMillis() + 10_000;
            try (var watcher = FileSystems.getDefault().newWatchService()) {
                DaemonPaths.base().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                while (System.currentTimeMillis() < deadline) {
                    if (DaemonClient.isAlive()) {
                        System.out.println(
                                styles.checkmark() + " " + styles.bold("Daemon started."));
                        System.out.println(styles.gray("  Socket: " + DaemonPaths.socket()));
                        System.out.println(styles.gray("  Log:    " + DaemonPaths.logFile()));
                        return;
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    WatchKey key = watcher.poll(remaining, TimeUnit.MILLISECONDS);
                    if (key != null) key.reset();
                }
            }
            System.err.println(styles.error("Daemon did not start within 10 s."));
            System.err.println(styles.gray("  Check log: " + DaemonPaths.logFile()));
        }
    }

    // — stop ———————————————————————————————————————————————————————————————

    /// Stops a running daemon gracefully.
    ///
    /// If the systemd user service is currently {@code active}, delegates to
    /// {@code systemctl --user stop} so systemd updates its state (which in turn
    /// runs {@code ExecStop} → direct socket stop). Otherwise, sends a {@code stop}
    /// frame directly over the Unix socket.
    ///
    /// ### Avoiding circular calls
    /// When systemd runs {@code ExecStop} it calls this command. At that point the
    /// service state is {@code deactivating} — {@code systemctl is-active} returns
    /// non-zero — so the command falls through to the direct socket stop instead of
    /// re-invoking systemctl.
    @Command(name = "stop", description = "Stop the running daemon")
    static class Stop extends HensuCommand {

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);

            if (!DaemonClient.isAlive()) {
                System.out.println(styles.gray("No daemon is running."));
                return;
            }

            // Delegate to the platform service manager when it owns the process so its
            // state stays consistent. Falls through to direct socket stop otherwise.
            if (isSystemdServiceActive()) {
                // When called from ExecStop the service is "deactivating" →
                // isSystemdServiceActive() returns false → reaches direct stop below.
                try {
                    new ProcessBuilder(SystemBinaries.systemctl(), "--user", "stop", "hensu-daemon")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start()
                            .waitFor();
                    System.out.println(styles.checkmark() + " " + styles.bold("Daemon stopped."));
                } catch (Exception e) {
                    System.err.println(
                            styles.error("Failed to stop via systemd: " + e.getMessage()));
                }
                return;
            }

            if (isLaunchdAgentLoaded()) {
                try {
                    new ProcessBuilder(SystemBinaries.launchctl(), "stop", "io.hensu.daemon")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start()
                            .waitFor();
                    System.out.println(styles.checkmark() + " " + styles.bold("Daemon stopped."));
                } catch (Exception e) {
                    System.err.println(
                            styles.error("Failed to stop via launchctl: " + e.getMessage()));
                }
                return;
            }

            // Direct socket stop: unmanaged daemon.
            try {
                new DaemonClient().stop();
                System.out.println(styles.checkmark() + " " + styles.bold("Daemon stopped."));
            } catch (IOException e) {
                System.err.println(styles.error("Failed to stop daemon: " + e.getMessage()));
            }
        }
    }

    // — status —————————————————————————————————————————————————————————————

    /// Shows daemon health and the count of tracked executions.
    @Command(name = "status", description = "Show daemon status")
    static class Status extends HensuCommand {

        @Override
        protected void execute() {
            AnsiStyles styles = AnsiStyles.of(true);

            if (!DaemonClient.isAlive()) {
                System.out.println(
                        styles.statusDot("CANCELLED")
                                + " "
                                + styles.bold("Daemon is not running."));
                System.out.println(styles.gray("  Start it with: hensu daemon start"));
                return;
            }

            try {
                var executions = new DaemonClient().list();
                long running =
                        executions.stream().filter(e -> "RUNNING".equals(e.status())).count();

                System.out.println(
                        styles.statusDot("RUNNING") + " " + styles.bold("Daemon is running."));
                System.out.println(styles.gray("  Socket:     " + DaemonPaths.socket()));
                System.out.printf(
                        styles.gray("  Executions: %d running, %d total%n"),
                        running,
                        executions.size());

            } catch (IOException e) {
                System.err.println(styles.error("Failed to query daemon: " + e.getMessage()));
            }
        }
    }

    // — systemd helpers ————————————————————————————————————————————————————

    /// Returns {@code true} if the Hensu systemd user service unit is installed.
    ///
    /// Checks {@code $XDG_CONFIG_HOME/systemd/user/hensu-daemon.service}, falling
    /// back to {@code ~/.config/systemd/user/hensu-daemon.service}.
    static boolean isSystemdServiceInstalled() {
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        Path configBase =
                (xdgConfig != null && !xdgConfig.isBlank())
                        ? Path.of(xdgConfig)
                        : Path.of(System.getProperty("user.home"), ".config");
        return Files.exists(configBase.resolve("systemd/user/hensu-daemon.service"));
    }

    /// Returns {@code true} if the Hensu daemon service is currently {@code active}
    /// according to systemd.
    ///
    /// Returns {@code false} if the service is {@code deactivating}, {@code inactive},
    /// {@code failed}, or if {@code systemctl} is not available.
    static boolean isSystemdServiceActive() {
        try {
            return new ProcessBuilder(
                                    SystemBinaries.systemctl(),
                                    "--user",
                                    "is-active",
                                    "--quiet",
                                    "hensu-daemon")
                            .start()
                            .waitFor()
                    == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /// Returns {@code true} if the Hensu launchd user agent plist is installed.
    ///
    /// Checks {@code ~/Library/LaunchAgents/io.hensu.daemon.plist}.
    static boolean isLaunchdAgentInstalled() {
        return Files.exists(
                Path.of(
                        System.getProperty("user.home"),
                        "Library/LaunchAgents/io.hensu.daemon.plist"));
    }

    /// Returns {@code true} if the Hensu launchd user agent is currently loaded.
    ///
    /// Runs {@code launchctl list io.hensu.daemon}; exit code 0 means loaded.
    /// Returns {@code false} if {@code launchctl} is unavailable or the agent is not loaded.
    static boolean isLaunchdAgentLoaded() {
        try {
            return new ProcessBuilder(SystemBinaries.launchctl(), "list", "io.hensu.daemon")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor()
                    == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
