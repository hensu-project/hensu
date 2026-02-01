package io.hensu.cli.visualizer;

import io.hensu.core.workflow.Workflow;

/// Strategy interface for rendering workflows in different output formats.
///
/// Implementations are discovered via CDI and registered in {@link WorkflowVisualizer}.
/// Each implementation provides a unique format name for selection.
///
/// ### Built-in Formats
/// - `text` - ASCII art with ANSI colors ({@link TextVisualizationFormat})
/// - `mermaid` - Mermaid diagram syntax ({@link MermaidVisualizationFormat})
///
/// @see WorkflowVisualizer
/// @see TextVisualizationFormat
/// @see MermaidVisualizationFormat
public interface VisualizationFormat {

    /// Returns the unique identifier for this format.
    ///
    /// @return format name used for CLI selection (e.g., "text", "mermaid"), never null
    String getName();

    /// Renders the workflow graph in this format.
    ///
    /// @param workflow the workflow to visualize, not null
    /// @return formatted string representation, never null
    String render(Workflow workflow);
}
