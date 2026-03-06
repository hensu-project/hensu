package io.hensu.cli.execution;

import io.hensu.cli.visualizer.TextVisualizationFormat;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/// Factory for creating {@link VerboseExecutionListener} instances with CDI-injected dependencies.
///
/// Injects the {@link TextVisualizationFormat} for node rendering and creates listener
/// instances configured for specific workflow executions.
///
/// @implNote Application-scoped. Thread-safe; creates new listener instances per call.
/// @see VerboseExecutionListener
@ApplicationScoped
public class VerboseExecutionListenerFactory {

    @Inject private TextVisualizationFormat visualizer;

    /// Creates a verbose execution listener writing to the given stream.
    ///
    /// Used for both inline and daemon execution. The caller supplies the terminal width
    /// so box-drawing aligns correctly regardless of how the listener is invoked.
    ///
    /// @param workflow  the workflow being executed, not null
    /// @param out       output stream for agent I/O display, not null
    /// @param useColor  whether to apply ANSI color codes
    /// @param termWidth terminal width in columns for box-drawing alignment
    /// @return configured listener writing to {@code out}, never null
    public ExecutionListener create(
            Workflow workflow, java.io.PrintStream out, boolean useColor, int termWidth) {
        return new VerboseExecutionListener(out, useColor, workflow, visualizer, termWidth);
    }
}
