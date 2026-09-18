package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.SandboxPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Everything a command needs in order to run, decided but not yet launched.
///
/// Preparation and execution are separate so that a human approval gate can show
/// a reviewer the exact argv, environment and containment that will be used, and
/// then run precisely that. A gate that re-derived the command after approving a
/// description of it would be approving one thing and running another.
///
/// ### Contracts
/// - **Precondition**: produced only by {@link CommandRunner#prepare}
/// - **Postcondition**: `argv` is final – already bound, already sandbox-wrapped
/// - **Invariant**: `environment` is the complete environment of the child; the
///   host environment is not inherited
///
/// @param argv the final command line including the sandbox supervisor, not null
/// @param environment the child's complete environment, not null
/// @param workingDir the directory the command runs in, not null
/// @param timeoutMs the wall clock bounding the whole process tree, positive
/// @param policy the containment scope that was compiled, not null
/// @param sandboxState the backend that wrapped it, or `unsandboxed-override`
///     when an operator disabled containment, not null
/// @param callDirectory the per-call scratch directory holding the private home,
///     deleted after execution, not null
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandRunner#execute for the other half
public record PreparedCommand(
        List<String> argv,
        Map<String, String> environment,
        Path workingDir,
        long timeoutMs,
        SandboxPolicy policy,
        String sandboxState,
        Path callDirectory) {

    /// Recorded as the sandbox state when an operator turned containment off.
    public static final String UNSANDBOXED_OVERRIDE = "unsandboxed-override";

    /// Compact constructor taking defensive copies.
    public PreparedCommand {
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
    }
}
