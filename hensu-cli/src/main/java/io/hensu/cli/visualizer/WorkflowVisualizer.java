package io.hensu.cli.visualizer;

import io.hensu.core.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;

/// Registry for workflow visualization formats. Discovers all VisualizationFormat implementations
/// via CDI.
@ApplicationScoped
public class WorkflowVisualizer {

    private final Map<String, VisualizationFormat> formats;

    @Inject
    public WorkflowVisualizer(Instance<VisualizationFormat> formatInstances) {
        this.formats =
                formatInstances.stream()
                        .collect(Collectors.toMap(VisualizationFormat::getName, format -> format));
    }

    /// Visualize workflow using the specified format.
    ///
    /// @param workflow The workflow to visualize
    /// @param formatName The format name (text, mermaid)
    /// @return The formatted visualization
    /// @throws IllegalArgumentException if format is not supported
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

    /// Visualize workflow using the default text format.
    ///
    /// @param workflow The workflow to visualize
    /// @return The text visualization
    public String visualize(Workflow workflow) {
        return visualize(workflow, "text");
    }

    /// @return Available format names
    public Iterable<String> getAvailableFormats() {
        return formats.keySet();
    }
}
