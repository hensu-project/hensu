package io.hensu.cli.execution;

import io.hensu.cli.ui.AnsiStyles;
import io.hensu.cli.visualizer.TextVisualizationFormat;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import java.io.PrintStream;

/// Execution listener that prints detailed workflow progress to the terminal.
///
/// Displays agent inputs and outputs in styled boxes with semantic coloring.
/// Optionally renders node visualizations showing transitions before each agent call.
///
/// ### Output Format
/// ```
/// ┌─ input · nodeId → agentId ─────────────────────────────────
///   (prompt content)
/// └────────────────────────────────────────────────────────────
///
/// ┌─ output · nodeId ← agentId · OK ──────────────────────────
///   (response content)
/// └────────────────────────────────────────────────────────────
/// ```
///
/// @implNote **Not thread-safe**. Output may interleave if used with parallel execution.
/// @see VerboseExecutionListenerFactory
/// @see io.hensu.core.execution.ExecutionListener
public class VerboseExecutionListener implements ExecutionListener {

    private final PrintStream out;
    private final AnsiStyles styles;
    private final boolean useColor;
    private final Workflow workflow;
    private final TextVisualizationFormat visualizer;
    private final int termWidth;

    /// Creates a verbose listener with explicit terminal width for box-drawing alignment.
    ///
    /// @param out        output stream for printing (typically System.out), not null
    /// @param useColor   whether to apply ANSI color codes
    /// @param workflow   the workflow being executed for node lookup, may be null
    ///                   to skip visualization
    /// @param visualizer text visualizer for node rendering, may be null to skip visualization
    /// @param termWidth  terminal width in columns (caller reads {@code COLUMNS} or defaults to 80)
    public VerboseExecutionListener(
            PrintStream out,
            boolean useColor,
            Workflow workflow,
            TextVisualizationFormat visualizer,
            int termWidth) {
        this.out = out;
        this.styles = AnsiStyles.of(useColor);
        this.useColor = useColor;
        this.workflow = workflow;
        this.visualizer = visualizer;
        this.termWidth = termWidth;
    }

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        if (workflow != null && visualizer != null) {
            Node node = workflow.getNodes().get(nodeId);
            if (node != null) {
                out.print(visualizer.renderNode(node, nodeId, useColor));
            }
        }

        String label = styles.dim("input · ") + nodeId + " " + styles.arrow() + " " + agentId;
        out.println(styles.boxTopWithLabel(label, termWidth));
        printIndented(prompt, false);
        out.println(styles.separatorBottom(termWidth));
        out.println();
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        boolean isSuccess = !(response instanceof AgentResponse.Error);
        String arrow = styles.successOrError("←", isSuccess);
        String statusTag = styles.successOrError(isSuccess ? "OK" : "ERROR", isSuccess);
        String label =
                styles.dim("output · ")
                        + nodeId
                        + " "
                        + arrow
                        + " "
                        + agentId
                        + styles.dim(" · ")
                        + statusTag;
        out.println(styles.boxTopWithLabel(label, termWidth));
        printIndented(extractOutput(response), true);
        out.println(styles.separatorBottom(termWidth));
        out.println();
    }

    private String extractOutput(AgentResponse response) {
        return switch (response) {
            case AgentResponse.TextResponse t -> t.content();
            case AgentResponse.ToolRequest t -> "Tool: " + t.toolName() + " - " + t.reasoning();
            case AgentResponse.PlanProposal p -> "Plan with " + p.steps().size() + " steps";
            case AgentResponse.Error e -> e.message();
        };
    }

    private void printIndented(String text, boolean isOutput) {
        if (text == null || text.isEmpty()) {
            out.println("  " + styles.gray("(empty)"));
            return;
        }
        for (String line : text.split("\n")) {
            out.println("  " + (isOutput ? line : styles.gray(line)));
        }
    }
}
