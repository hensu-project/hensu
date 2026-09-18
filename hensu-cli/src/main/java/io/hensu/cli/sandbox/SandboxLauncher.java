package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.SandboxPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Compiles a {@link SandboxPolicy} into the argv of an OS containment supervisor.
///
/// Once a catalog contains any interpreter or build tool – `python`, a shell
/// script, `./gradlew` – the set of programs that can execute is unbounded and
/// invisible from argv: a permitted script can spawn anything. Inspecting what
/// the agent typed cannot see that, so containment is delegated to the operating
/// system, which stops a grandchild exactly as it stops the first process and
/// never needs to understand either.
///
/// ### Permitted implementations
/// - {@link BwrapSandboxLauncher} – Linux, kernel namespaces through `bwrap`
/// - {@link SeatbeltSandboxLauncher} – macOS, a generated SBPL profile through
///   `sandbox-exec`
/// - {@link UnavailableSandboxLauncher} – every other platform, which declines
///   rather than pretending
///
/// Each backend is an external binary invoked through `ProcessBuilder`: no JNI,
/// no reflection, nothing that would trouble a native image.
///
/// ### Availability means working, not installed
/// {@link #isAvailable()} reports the result of a probe that ran the full
/// mechanism once at construction. A backend that is installed but refused by the
/// kernel – unprivileged user namespaces disabled, a profile that will not
/// compile – is unavailable, and {@link #unavailabilityReason()} carries what the
/// probe said. A caller that finds no working backend must refuse the call rather
/// than run it unconfined.
///
/// @implNote **Immutable after construction. Thread-safe.** The probe runs once;
/// {@link #wrap} is a pure function of its arguments.
/// @see CommandRunner for the caller that refuses when no backend works
public interface SandboxLauncher {

    /// Returns whether the backend ran its probe successfully.
    ///
    /// @return true if commands can be contained on this host
    boolean isAvailable();

    /// Returns why the backend is unusable.
    ///
    /// @return the probe's own diagnostics, or a statement that the platform has
    ///     no backend; empty when the backend is available
    String unavailabilityReason();

    /// Returns the short name of the mechanism, recorded in prepared commands and audit events.
    ///
    /// @return the backend identifier, for example `bwrap` or `seatbelt`, never null
    String backendName();

    /// Wraps a command in the supervisor that will contain it.
    ///
    /// ### Contracts
    /// - **Precondition**: {@link #isAvailable()} is true; `workingDir`, every
    ///   `write:` subtree, every `cache:` entry and `privateHome` already exist
    /// - **Postcondition**: the returned argv runs `argv` under the policy, with
    ///   the command's own configuration files masked read-only so a `write:`
    ///   subtree covering them cannot make the catalog agent-writable
    ///
    /// @param argv the command to contain, not null or empty
    /// @param policy the containment scope, not null
    /// @param workingDir the directory the command runs in, not null
    /// @param privateHome the per-call home directory to bind writable, not null
    /// @return the supervised argv, never null
    /// @throws UnsupportedOperationException if the backend is unavailable
    List<String> wrap(List<String> argv, SandboxPolicy policy, Path workingDir, Path privateHome);

    /// Returns the backend for the host this process runs on.
    ///
    /// The instance is created once and its probe runs once, so repeated calls
    /// cost nothing and every caller agrees on whether containment works here.
    ///
    /// @return the platform backend, never null
    static SandboxLauncher forCurrentOs() {
        return Holder.INSTANCE;
    }

    /// Holds the lazily probed platform backend.
    ///
    /// @implNote Class initialization gives the laziness and the thread safety;
    /// no lock and no `ThreadLocal` are involved.
    final class Holder {

        private static final SandboxLauncher INSTANCE = select();

        private Holder() {}

        private static SandboxLauncher select() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                return new BwrapSandboxLauncher();
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return new SeatbeltSandboxLauncher();
            }
            return new UnavailableSandboxLauncher(os);
        }
    }
}
