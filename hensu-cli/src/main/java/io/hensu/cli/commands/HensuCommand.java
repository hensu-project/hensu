package io.hensu.cli.commands;

/// Minimal abstract base for all Hensu CLI commands.
///
/// Owns the {@link #run()} / {@link #execute()} contract.
/// Subclasses provide command-specific option sets and implement {@link #execute()}.
///
/// @see WorkflowCommand
/// @see ServerCommand
public abstract class HensuCommand implements Runnable {

    @Override
    public final void run() {
        execute();
    }

    protected abstract void execute();
}
