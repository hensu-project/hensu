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
/// ┌─────────────────────────────────────────────────────────────
///   * INPUT [nodeId] → agentId
///  ─────────────────────────────────────────────────────────────
///   (prompt content)
/// └─────────────────────────────────────────────────────────────
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

    /// Creates a verbose listener with full configuration.
    ///
    /// @param out        output stream for printing (typically System.out), not null
    /// @param useColor   whether to apply ANSI color codes
    /// @param workflow   the workflow being executed for node lookup, may be null
    /// to skip visualization
    /// @param visualizer text visualizer for node rendering, may be null to skip visualization
    public VerboseExecutionListener(
            PrintStream out,
            boolean useColor,
            Workflow workflow,
            TextVisualizationFormat visualizer) {
        this.out = out;
        this.styles = AnsiStyles.of(useColor);
        this.useColor = useColor;
        this.workflow = workflow;
        this.visualizer = visualizer;
    }

    @Override
    public void onAgentStart(String nodeId, String agentId, String prompt) {
        if (workflow != null && visualizer != null) {
            Node node = workflow.getNodes().get(nodeId);
            if (node != null) {
                out.print(visualizer.renderNode(node, nodeId, useColor));
            }
        }

        // INPUT block rendering
        out.println(styles.separatorTop());
        out.printf(
                "  %s %s [%s] %s %s%n",
                styles.accent("*"), styles.bold("INPUT"), nodeId, styles.arrow(), agentId);
        out.println(styles.separatorMid());
        printIndented(prompt, false);
        out.println(styles.separatorBottom());
        out.println();
    }

    @Override
    public void onAgentComplete(String nodeId, String agentId, AgentResponse response) {
        out.println(styles.separatorTop());
        String status = styles.successOrError("OK", response.isSuccess());
        String leftArrow = styles.successOrError("←", response.isSuccess());
        String marker = styles.successOrError("*", response.isSuccess());
        out.printf(
                "  %s %s [%s] %s %s (%s)%n",
                marker, styles.bold("OUTPUT"), nodeId, leftArrow, agentId, status);
        out.println(styles.separatorMid());
        printIndented(response.getOutput(), true);
        out.println(styles.separatorBottom());
        out.println();
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