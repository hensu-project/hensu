package io.hensu.cli.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Runs a short diagnostic command and reports what it said.
///
/// Both sandbox backends answer the same question at construction – does the full
/// mechanism actually work on this host – and a wrong answer has the same cost in
/// both: either commands are refused that could have been contained, or they run
/// believing in containment that is not there. The probe is shared so the two
/// backends cannot drift in how they decide.
///
/// @implNote **Stateless.** All members are static; safe to call from any thread.
final class ProcessProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ProcessProbe() {}

    /// Runs a command to completion and captures its merged output.
    ///
    /// @param argv the command to run, not null or empty
    /// @return the outcome, never null
    static Result run(List<String> argv) {
        Process process = null;
        try {
            process =
                    new ProcessBuilder(argv)
                            .redirectErrorStream(true)
                            .redirectInput(ProcessBuilder.Redirect.from(devNull()))
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .trim();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(false, argv.getFirst() + " probe timed out");
            }
            return process.exitValue() == 0
                    ? new Result(true, "")
                    : new Result(
                            false,
                            output.isEmpty()
                                    ? argv.getFirst() + " probe exited with " + process.exitValue()
                                    : output);
        } catch (IOException e) {
            return new Result(false, argv.getFirst() + " could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(false, argv.getFirst() + " probe was interrupted");
        }
    }

    private static java.io.File devNull() {
        return new java.io.File(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "NUL"
                        : "/dev/null");
    }

    /// The outcome of one probe.
    ///
    /// @param ok whether the command completed successfully
    /// @param diagnostics what the command said when it did not, never null (empty on success)
    record Result(boolean ok, String diagnostics) {}
}
