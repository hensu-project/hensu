package io.hensu.cli.visualizer;

import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/// ASCII text visualization format for workflows with ANSI color support.
///
/// Renders workflow graphs as indented box-drawing characters with semantic coloring
/// for different node types and transition states. Supports both colored and plain text output.
///
/// ### Node Type Colors
/// - **Blue (accent)**: STANDARD, GENERIC, PARALLEL, FORK, JOIN
/// - **Green (success)**: END, ACTION
/// - **Yellow (warn)**: LOOP
/// - **Gray**: SUB_WORKFLOW
///
/// @implNote Thread-safe. Each render call creates its own AnsiStyles instance.
/// @see MermaidVisualizationFormat for diagram output
@ApplicationScoped
public class TextVisualizationFormat implements VisualizationFormat {

    @Override
    public String getName() {
        return "text";
    }

    @Override
    public String render(Workflow workflow) {
        return render(workflow, true);
    }

    /// Renders workflow graph with configurable color support.
    ///
    /// Performs a breadth-first traversal from the start node, rendering each node
    /// with indentation reflecting its depth in the graph.
    ///
    /// @param workflow the workflow to visualize, not null
    /// @param useColor whether to apply ANSI color codes
    /// @return formatted text representation, never null
    public String render(Workflow workflow, boolean useColor) {
        AnsiStyles styles = AnsiStyles.of(useColor);
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "%s %s%n",
                        styles.bold("Workflow:"), styles.accent(workflow.getMetadata().getName())));
        sb.append(styles.gray("─".repeat(50))).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        Set<String> visited = new HashSet<>();
        Deque<NodeLevel> queue = new ArrayDeque<>();
        queue.add(new NodeLevel(workflow.getStartNode(), 0));

        while (!queue.isEmpty()) {
            NodeLevel current = queue.removeFirst();
            String nodeId = current.nodeId;
            int level = current.level;

            if (visited.contains(nodeId)) continue;
            visited.add(nodeId);

            String indent = "  ".repeat(level);
            Node node = workflow.getNodes().get(nodeId);
            if (node == null) continue;

            sb.append(renderNode(node, nodeId, indent, styles));
            sb.append(System.lineSeparator());

            collectNextNodes(node, level, queue);
        }

        return sb.toString();
    }

    /// Renders a single workflow node as a styled box.
    ///
    /// Used by {@link io.hensu.cli.execution.VerboseExecutionListener}
    /// to display node details during execution.
    /// Output includes node type, agent/executor info, and transition rules.
    ///
    /// @param node     the node to render, not null
    /// @param nodeId   the node identifier for display, not null
    /// @param useColor whether to apply ANSI color codes
    /// @return formatted node box, never null
    public String renderNode(Node node, String nodeId, boolean useColor) {
        return renderNode(node, nodeId, "", AnsiStyles.of(useColor));
    }

    private String renderNode(Node node, String nodeId, String indent, AnsiStyles styles) {
        StringBuilder sb = new StringBuilder();

        String styledNodeId = colorByNodeType(nodeId, node.getNodeType(), styles);

        sb.append(
                String.format(
                        "%s%s %s %s%n",
                        indent,
                        styles.boxTop(),
                        styledNodeId,
                        styles.gray("(" + node.getNodeType().name() + ")")));

        switch (node) {
            case StandardNode standardNode -> {
                if (standardNode.getAgentId() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "agent",
                                    styles.bold(standardNode.getAgentId())));
                }
                if (standardNode.getRubricId() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent, styles.boxMid(), "rubric", standardNode.getRubricId()));
                }
                if (standardNode.getReviewConfig() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "review",
                                    styles.warn(
                                            standardNode.getReviewConfig().getMode().toString())));
                }
                appendTransitions(sb, indent, standardNode.getTransitionRules(), styles);
            }
            case LoopNode loopNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "max",
                                styles.accent(String.valueOf(loopNode.getMaxIterations()))));
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent, styles.boxMid(), "condition", loopNode.getCondition()));
                if (loopNode.getBreakRules() != null) {
                    for (BreakRule rule : loopNode.getBreakRules()) {
                        sb.append(
                                String.format(
                                        "%s%s  %s %-14s %s%n",
                                        indent,
                                        styles.boxMid(),
                                        styles.arrow(),
                                        styles.bold(rule.getTargetNode()),
                                        styles.dim("on break")));
                    }
                }
            }
            case ParallelNode parallelNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "branches",
                                styles.accent(String.valueOf(parallelNode.getBranches().length))));
                for (Branch branch : parallelNode.getBranches()) {
                    sb.append(
                            String.format(
                                    "%s%s    %s %s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    styles.bullet(),
                                    branch.getId(),
                                    styles.dim("(" + branch.getAgentId() + ")")));
                }
                if (parallelNode.getConsensusConfig() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "consensus",
                                    styles.warn(
                                            parallelNode
                                                    .getConsensusConfig()
                                                    .getStrategy()
                                                    .toString())));
                }
            }
            case ForkNode forkNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "targets",
                                styles.accent(String.valueOf(forkNode.getTargets().size()))));
                for (String target : forkNode.getTargets()) {
                    sb.append(
                            String.format(
                                    "%s%s    %s %s%n",
                                    indent, styles.boxMid(), styles.bullet(), styles.bold(target)));
                }
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "wait all",
                                styles.successOrWarn(
                                        String.valueOf(forkNode.isWaitForAll()),
                                        forkNode.isWaitForAll())));
                appendTransitions(sb, indent, forkNode.getTransitionRules(), styles);
            }
            case JoinNode joinNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "awaiting",
                                styles.accent(String.valueOf(joinNode.getAwaitTargets().size()))));
                for (String target : joinNode.getAwaitTargets()) {
                    sb.append(
                            String.format(
                                    "%s%s    %s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    styles.bullet(),
                                    styles.accent(target)));
                }
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "merge",
                                styles.warn(joinNode.getMergeStrategy().toString())));
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "writes",
                                String.join(", ", joinNode.getWrites())));
                appendTransitions(sb, indent, joinNode.getTransitionRules(), styles);
            }
            case GenericNode genericNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "executor",
                                styles.accent(genericNode.getExecutorType())));
                if (!genericNode.getConfig().isEmpty()) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "config",
                                    styles.accent(String.valueOf(genericNode.getConfig().size()))
                                            + " entries"));
                }
                if (genericNode.getRubricId() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent, styles.boxMid(), "rubric", genericNode.getRubricId()));
                }
                appendTransitions(sb, indent, genericNode.getTransitionRules(), styles);
            }
            case ActionNode actionNode -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "actions",
                                styles.accent(String.valueOf(actionNode.getActions().size()))));
                for (var action : actionNode.getActions()) {
                    String actionType = action.getClass().getSimpleName();
                    sb.append(
                            String.format(
                                    "%s%s    %s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    styles.bullet(),
                                    styles.accent(actionType)));
                }
                appendTransitions(sb, indent, actionNode.getTransitionRules(), styles);
            }
            case EndNode endNode -> {
                boolean isSuccess = endNode.getExitStatus().toString().equals("SUCCESS");
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "exit",
                                styles.successOrError(
                                        endNode.getExitStatus().toString(), isSuccess)));
            }
            default -> {}
        }

        sb.append(String.format("%s%s%n", indent, styles.boxBottom()));
        return sb.toString();
    }

    private void appendTransitions(
            StringBuilder sb,
            String indent,
            java.util.List<TransitionRule> rules,
            AnsiStyles styles) {
        if (rules.isEmpty()) {
            return;
        }
        for (TransitionRule rule : rules) {
            if (rule instanceof SuccessTransition success) {
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(success.getTargetNode()),
                                styles.dim("on success")));
            } else if (rule instanceof FailureTransition failure) {
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(failure.getThenTargetNode()),
                                styles.dim("on failure  retry " + failure.getRetryCount())));
            } else if (rule instanceof ScoreTransition score) {
                for (ScoreCondition cond : score.getConditions()) {
                    String condValue =
                            cond.getOperator() == ComparisonOperator.RANGE && cond.range() != null
                                    ? cond.range().start() + ".." + cond.range().end()
                                    : String.valueOf(cond.getValue());
                    sb.append(
                            String.format(
                                    "%s%s  %s %-14s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    styles.arrow(),
                                    styles.bold(cond.getTargetNode()),
                                    styles.dim("score " + cond.getOperator() + " " + condValue)));
                }
            }
        }
    }

    /// Colors text based on node type using semantic color methods.
    private String colorByNodeType(String text, NodeType type, AnsiStyles styles) {
        return switch (type) {
            case STANDARD, GENERIC, PARALLEL, FORK, JOIN -> styles.accent(text);
            case END, ACTION -> styles.success(text);
            case LOOP -> styles.warn(text);
            case SUB_WORKFLOW -> styles.gray(text);
        };
    }

    private void collectNextNodes(Node node, int level, Deque<NodeLevel> queue) {
        switch (node) {
            case StandardNode standardNode -> {
                for (TransitionRule rule : standardNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition success) {
                        queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                    } else if (rule instanceof FailureTransition failure) {
                        queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                    } else if (rule instanceof ScoreTransition score) {
                        for (ScoreCondition cond : score.getConditions()) {
                            queue.add(new NodeLevel(cond.getTargetNode(), level + 1));
                        }
                    }
                }
            }
            case LoopNode loopNode -> {
                if (loopNode.getBreakRules() != null) {
                    for (BreakRule rule : loopNode.getBreakRules()) {
                        queue.add(new NodeLevel(rule.getTargetNode(), level + 1));
                    }
                }
            }
            case ForkNode forkNode -> {
                for (String target : forkNode.getTargets()) {
                    queue.add(new NodeLevel(target, level + 1));
                }
                for (TransitionRule rule : forkNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition success) {
                        queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                    }
                }
            }
            case JoinNode joinNode -> {
                for (TransitionRule rule : joinNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition success) {
                        queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                    } else if (rule instanceof FailureTransition failure) {
                        queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                    }
                }
            }
            case GenericNode genericNode -> {
                for (TransitionRule rule : genericNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition success) {
                        queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                    } else if (rule instanceof FailureTransition failure) {
                        queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                    } else if (rule instanceof ScoreTransition score) {
                        for (ScoreCondition cond : score.getConditions()) {
                            queue.add(new NodeLevel(cond.getTargetNode(), level + 1));
                        }
                    }
                }
            }
            case ActionNode actionNode -> {
                for (TransitionRule rule : actionNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition success) {
                        queue.add(new NodeLevel(success.getTargetNode(), level + 1));
                    } else if (rule instanceof FailureTransition failure) {
                        queue.add(new NodeLevel(failure.getThenTargetNode(), level + 1));
                    }
                }
            }
            default -> {}
        }
    }

    private record NodeLevel(String nodeId, int level) {}
}
