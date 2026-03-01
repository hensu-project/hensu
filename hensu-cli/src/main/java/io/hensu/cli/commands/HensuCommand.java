package io.hensu.cli.commands;

/// Minimal abstract base for all Hensu CLI commands.
///
/// Owns the banner display and the {@link #run()} / {@link #execute()} contract.
/// Subclasses provide command-specific option sets and implement {@link #execute()}.
///
/// @see WorkflowCommand
/// @see ServerCommand
public abstract class HensuCommand implements Runnable {

    private static final String[] BANNER = {
        "",
        "  _",
        " | |__    ___  _ __   ___  _   _",
        " | '_ \\  / _ \\| '_ \\ / __|| | | |",
        " | | | ||  __/| | | |\\__ \\| |_| |",
        " |_| |_| \\___||_| |_||___/ \\__,_|",
        "",
        " The Agentic Workflow Engine",
        ""
    };

    @Override
    public final void run() {
        for (String line : BANNER) {
            System.out.println(line);
        }
        execute();
    }

    protected abstract void execute();
}
