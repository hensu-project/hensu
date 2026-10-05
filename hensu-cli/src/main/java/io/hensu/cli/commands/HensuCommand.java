package io.hensu.cli.commands;

import java.util.concurrent.Callable;

/// Minimal abstract base for all Hensu CLI commands.
///
/// Owns the {@link #call()} / {@link #execute()} contract and the process exit code.
/// Subclasses provide command-specific option sets, implement {@link #execute()}, and
/// call {@link #fail()} wherever they report a failure to the operator, so a script or
/// a CI job running the command sees it fail rather than a zero exit after an error
/// message.
///
/// @see WorkflowCommand
/// @see ServerCommand
public abstract class HensuCommand implements Callable<Integer> {

    /// Exit code of an invocation that reported a failure.
    static final int EXIT_FAILURE = 1;

    private int exitCode;

    @Override
    public final Integer call() {
        execute();
        return exitCode;
    }

    protected abstract void execute();

    /// Marks this invocation failed, so the process exits non-zero.
    ///
    /// The command still prints its own explanation; this only decides what the shell sees.
    protected final void fail() {
        exitCode = EXIT_FAILURE;
    }
}
