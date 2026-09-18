package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.SandboxPolicy;
import java.nio.file.Path;
import java.util.List;

/// The backend for platforms Hensu cannot contain a process tree on.
///
/// Windows has no containment primitive reachable as a plain external binary, and
/// shipping a weak approximation would be worse than shipping none: it would let
/// a deployment believe in a boundary that is not there. This backend therefore
/// declines, and a command that needs a sandbox is refused or routed to a human
/// instead of quietly running unconfined. A real backend – AppContainer through a
/// helper, a container runtime – is a new implementation of
/// {@link SandboxLauncher} and nothing else.
///
/// @implNote **Immutable after construction. Thread-safe.**
/// @see SandboxLauncher for the contract
public final class UnavailableSandboxLauncher implements SandboxLauncher {

    /// Name recorded for the absence of a backend.
    public static final String BACKEND = "none";

    private final String reason;

    /// Creates the declining backend.
    ///
    /// @param osName the operating system that has no backend, not null
    public UnavailableSandboxLauncher(String osName) {
        this.reason = "no sandbox backend is available on " + osName;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailabilityReason() {
        return reason;
    }

    @Override
    public String backendName() {
        return BACKEND;
    }

    @Override
    public List<String> wrap(
            List<String> argv, SandboxPolicy policy, Path workingDir, Path privateHome) {
        throw new UnsupportedOperationException(reason);
    }
}
