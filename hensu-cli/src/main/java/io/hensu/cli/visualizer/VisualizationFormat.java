package io.hensu.cli.visualizer;

import io.hensu.core.workflow.Workflow;
import java.util.Map;

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

    /// Renders the workflow graph with sub-workflows available for inlining.
    ///
    /// Sub-workflows matching a {@link io.hensu.core.workflow.node.SubWorkflowNode}'s
    /// workflow ID are rendered inline within the parent graph.
    ///
    /// @param workflow     the root workflow to visualize, not null
    /// @param subWorkflows loaded sub-workflows keyed by workflow ID, not null (may be empty)
    /// @return formatted string representation, never null
    default String render(Workflow workflow, Map<String, Workflow> subWorkflows) {
        return render(workflow);
    }
}
