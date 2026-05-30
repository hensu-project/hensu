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

    /// Renders workflow with sub-workflows available for inlining.
    ///
    /// @param workflow     the root workflow to visualize, not null
    /// @param subWorkflows loaded sub-workflows keyed by workflow ID, not null
    /// @param formatName   the format name (e.g., "text", "mermaid"), not null
    /// @return formatted visualization string, never null
    /// @throws IllegalArgumentException if format is not registered
    public String visualize(
            Workflow workflow, Map<String, Workflow> subWorkflows, String formatName) {
        VisualizationFormat format = formats.get(formatName);
        if (format == null) {
            throw new IllegalArgumentException(
                    "Unsupported format: "
                            + formatName
                            + ". Available: "
                            + String.join(", ", formats.keySet()));
        }
        return format.render(workflow, subWorkflows);
    }
}
