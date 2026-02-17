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

    /// Creates a verbose execution listener for the specified workflow.
    ///
    /// @param workflow the workflow being executed, not null
    /// @param useColor whether to apply ANSI color codes
    /// @return configured listener writing to System.out, never null
    public ExecutionListener create(Workflow workflow, boolean useColor) {
        return new VerboseExecutionListener(System.out, useColor, workflow, visualizer);
    }
}
