package io.hensu.cli.visualizer;

import io.hensu.core.workflow.Workflow;

/// Interface for workflow visualization formats. Implementations provide different output formats
/// (text, mermaid).
public interface VisualizationFormat {

    /// @return The format name (e.g., "text", "mermaid")
    String getName();

    /// Render the workflow in this format.
    ///
    /// @param workflow The workflow to visualize
    /// @return The formatted string representation
    String render(Workflow workflow);
}
