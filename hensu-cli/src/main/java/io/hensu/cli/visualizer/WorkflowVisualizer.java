package io.hensu.cli.visualizer;

import io.hensu.core.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;

/// Registry and dispatcher for workflow visualization formats.
///
/// Discovers all {@link VisualizationFormat} implementations via CDI injection and provides
/// a unified API for rendering workflows in any registered format.
///
/// @implNote Thread-safe after construction. Format map is immutable.
/// @see VisualizationFormat
/// @see TextVisualizationFormat
/// @see MermaidVisualizationFormat
@ApplicationScoped
public class WorkflowVisualizer {

    private final Map<String, VisualizationFormat> formats;

    /// Creates a visualizer with all CDI-discovered format implementations.
    ///
    /// @param formatInstances CDI-provided format implementations, not null
    @Inject
    public WorkflowVisualizer(Instance<VisualizationFormat> formatInstances) {
        this.formats =
                formatInstances.stream()
                        .collect(Collectors.toMap(VisualizationFormat::getName, format -> format));
    }

    /// Renders workflow using the specified format.
    ///
    /// @param workflow   the workflow to visualize, not null
    /// @param formatName the format name (e.g., "text", "mermaid"), not null
    /// @return formatted visualization string, never null
    /// @throws IllegalArgumentException if format is not registered
    public String visualize(Workflow workflow, String formatName) {
        VisualizationFormat format = formats.get(formatName);
        if (format == null) {
            throw new IllegalArgumentException(
                    "Unsupported format: "
                            + formatName
                            + ". Available: "
                            + String.join(", ", formats.keySet()));
        }
        return format.render(workflow);
    }

    /// Renders workflow using the default text format.
    ///
    /// Equivalent to calling `visualize(workflow, "text")`.
    ///
    /// @param workflow the workflow to visualize, not null
    /// @return ASCII text visualization with ANSI colors, never null
    public String visualize(Workflow workflow) {
        return visualize(workflow, "text");
    }

    /// Returns the names of all registered visualization formats.
    ///
    /// @return iterable of format names (e.g., "text", "mermaid"), never null
    public Iterable<String> getAvailableFormats() {
        return formats.keySet();
    }
}
