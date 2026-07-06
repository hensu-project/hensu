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
import java.util.List;
import java.util.Map;
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

    private static final int SUB_WORKFLOW_BORDER_WIDTH = 46;

    @Override
    public String getName() {
        return "text";
    }

    @Override
    public String render(Workflow workflow) {
        return render(workflow, true);
    }

    @Override
    public String render(Workflow workflow, Map<String, Workflow> subWorkflows) {
        return render(workflow, subWorkflows, true);
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
        return render(workflow, Map.of(), useColor);
    }

    /// Renders workflow graph with sub-workflows and configurable color support.
    ///
    /// @param workflow     the workflow to visualize, not null
    /// @param subWorkflows loaded sub-workflows keyed by workflow ID, not null
    /// @param useColor     whether to apply ANSI color codes
    /// @return formatted text representation, never null
    public String render(Workflow workflow, Map<String, Workflow> subWorkflows, boolean useColor) {
        AnsiStyles styles = AnsiStyles.of(useColor);
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        "%s %s%n",
                        styles.bold("Workflow:"), styles.accent(workflow.getMetadata().getName())));
        sb.append(styles.gray("─".repeat(50))).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        renderWorkflowNodes(sb, workflow, subWorkflows, styles);

        return sb.toString();
    }

    /// Renders workflow graph with configurable color support (public
    /// for VerboseExecutionListener).
    ///
    /// @param node     the node to render, not null
    /// @param nodeId   the node identifier for display, not null
    /// @param useColor whether to apply ANSI color codes
    /// @return formatted node box, never null
    public String renderNode(Node node, String nodeId, boolean useColor) {
        return renderNode(node, nodeId, "", AnsiStyles.of(useColor));
    }

    private void renderWorkflowNodes(
            StringBuilder sb,
            Workflow workflow,
            Map<String, Workflow> subWorkflows,
            AnsiStyles styles) {
        Set<String> visited = new HashSet<>();
        Deque<NodeLevel> queue = new ArrayDeque<>();
        queue.add(new NodeLevel(workflow.getStartNode(), 0));

        while (!queue.isEmpty()) {
            NodeLevel current = queue.removeFirst();
            String nodeId = current.nodeId;
            int level = current.level;

            if (visited.contains(nodeId)) continue;
            visited.add(nodeId);

            Node node = workflow.getNodes().get(nodeId);
            if (node == null) continue;

            String indent = "  ".repeat(level);
            sb.append(renderNode(node, nodeId, indent, styles));
            sb.append(System.lineSeparator());

            if (node instanceof SubWorkflowNode swn) {
                Workflow sub = subWorkflows.get(swn.getWorkflowId());
                if (sub != null) {
                    renderSubWorkflowBlock(sb, sub, subWorkflows, level + 1, styles);
                    sb.append(System.lineSeparator());
                }
            }

            collectNextNodes(node, level, queue);
        }
    }

    private void renderSubWorkflowBlock(
            StringBuilder sb,
            Workflow subWorkflow,
            Map<String, Workflow> subWorkflows,
            int level,
            AnsiStyles styles) {
        String indent = "  ".repeat(level);

        // Render sub-workflow content into a buffer
        StringBuilder content = new StringBuilder();
        renderWorkflowNodes(content, subWorkflow, subWorkflows, styles);
        String contentStr = content.toString().stripTrailing();

        // Top border with label
        String label = styles.gray(subWorkflow.getMetadata().getName());
        sb.append(indent)
                .append(styles.boxTopWithLabel(label, SUB_WORKFLOW_BORDER_WIDTH))
                .append(System.lineSeparator());
        sb.append(indent).append(styles.boxMid()).append(System.lineSeparator());

        // Prefix each content line with border
        for (String line : contentStr.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                sb.append(indent).append(styles.boxMid()).append(System.lineSeparator());
            } else {
                sb.append(indent)
                        .append(styles.boxMid())
                        .append(" ")
                        .append(line)
                        .append(System.lineSeparator());
            }
        }

        // Bottom border
        sb.append(indent)
                .append(styles.separatorBottom(SUB_WORKFLOW_BORDER_WIDTH))
                .append(System.lineSeparator());
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
                if (standardNode.getRubric() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "rubric",
                                    standardNode.getRubric().getCriteria().size() + " criteria"));
                }
                if (standardNode.hasPlanningEnabled()) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "planning",
                                    standardNode.getPlanningConfig().mode()));
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
                appendTransitions(sb, indent, parallelNode.getTransitionRules(), styles);
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
                if (genericNode.getRubric() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    "rubric",
                                    genericNode.getRubric().getCriteria().size() + " criteria"));
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
            case SubWorkflowNode swn -> {
                sb.append(
                        String.format(
                                "%s%s  %-9s %s%n",
                                indent,
                                styles.boxMid(),
                                "workflow",
                                styles.bold(swn.getWorkflowId())));
                if (swn.getTargetVersion() != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %-9s %s%n",
                                    indent, styles.boxMid(), "version", swn.getTargetVersion()));
                }
                appendTransitions(sb, indent, swn.getTransitionRules(), styles);
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
            StringBuilder sb, String indent, List<TransitionRule> rules, AnsiStyles styles) {
        if (rules.isEmpty()) {
            return;
        }
        for (TransitionRule rule : rules) {
            if (rule instanceof BoundedTransition bounded) {
                appendBoundedTransition(sb, indent, bounded, styles);
            } else if (rule instanceof SuccessTransition success) {
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(success.getTargetNode()),
                                styles.dim("on success")));
            } else if (rule instanceof FailureTransition(String node)) {
                if (node != null) {
                    sb.append(
                            String.format(
                                    "%s%s  %s %-14s %s%n",
                                    indent,
                                    styles.boxMid(),
                                    styles.arrow(),
                                    styles.bold(node),
                                    styles.dim("on failure")));
                }
            } else if (rule instanceof NoConsensusTransition(String node, boolean fb)) {
                String label = fb ? "on no consensus +fb" : "on no consensus";
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(node),
                                styles.dim(label)));
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
            } else if (rule
                    instanceof
                    ApprovalTransition(boolean expected, String targetNode, boolean fb)) {
                String base = expected ? "on approval" : "on rejection";
                String label = fb ? base + " +fb" : base;
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(targetNode),
                                styles.dim(label)));
            } else if (rule instanceof ConditionTransition condition) {
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(condition.targetNode()),
                                styles.dim(conditionLabel(condition))));
            } else if (rule instanceof AlwaysTransition(String targetNode, boolean fb)) {
                String label = fb ? "otherwise +fb" : "otherwise";
                sb.append(
                        String.format(
                                "%s%s  %s %-14s %s%n",
                                indent,
                                styles.boxMid(),
                                styles.arrow(),
                                styles.bold(targetNode),
                                styles.dim(label)));
            }
        }
    }

    private String conditionLabel(ConditionTransition condition) {
        return condition.variable() + " " + condition.condition().describe();
    }

    private void appendBoundedTransition(
            StringBuilder sb, String indent, BoundedTransition bounded, AnsiStyles styles) {
        TransitionRule inner = bounded.trigger();
        String budget = "≤" + bounded.budget();

        if (inner instanceof FailureTransition(String node)) {
            String target = node != null ? node : "(self)";
            sb.append(
                    String.format(
                            "%s%s  %s %-14s %s%n",
                            indent,
                            styles.boxMid(),
                            styles.arrow(),
                            styles.bold(target),
                            styles.dim("on failure retry " + budget)));
        } else if (inner instanceof NoConsensusTransition(String node, boolean fb)) {
            String fbSuffix = fb ? " +fb" : "";
            sb.append(
                    String.format(
                            "%s%s  %s %-14s %s%n",
                            indent,
                            styles.boxMid(),
                            styles.arrow(),
                            styles.bold(node),
                            styles.dim("on no consensus revise " + budget + fbSuffix)));
        } else if (inner
                instanceof ApprovalTransition(boolean expected, String targetNode, boolean fb)) {
            String prefix = expected ? "on approval" : "on rejection";
            String fbSuffix = fb ? " +fb" : "";
            sb.append(
                    String.format(
                            "%s%s  %s %-14s %s%n",
                            indent,
                            styles.boxMid(),
                            styles.arrow(),
                            styles.bold(targetNode),
                            styles.dim(prefix + " revise " + budget + fbSuffix)));
        } else if (inner instanceof ScoreTransition score) {
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
                                styles.dim(
                                        "score "
                                                + cond.getOperator()
                                                + " "
                                                + condValue
                                                + " revise "
                                                + budget)));
            }
        } else if (inner instanceof ConditionTransition condition) {
            sb.append(
                    String.format(
                            "%s%s  %s %-14s %s%n",
                            indent,
                            styles.boxMid(),
                            styles.arrow(),
                            styles.bold(condition.targetNode()),
                            styles.dim(conditionLabel(condition) + " revise " + budget)));
        } else if (inner instanceof AlwaysTransition(String targetNode, boolean _)) {
            sb.append(
                    String.format(
                            "%s%s  %s %-14s %s%n",
                            indent,
                            styles.boxMid(),
                            styles.arrow(),
                            styles.bold(targetNode),
                            styles.dim("otherwise revise " + budget)));
        }

        // Escalation line
        sb.append(
                String.format(
                        "%s%s  %s %-14s %s%n",
                        indent,
                        styles.boxMid(),
                        styles.arrow(),
                        styles.bold(bounded.otherwise()),
                        styles.dim("budget exhausted")));
    }

    private String colorByNodeType(String text, NodeType type, AnsiStyles styles) {
        return switch (type) {
            case STANDARD, GENERIC, PARALLEL, FORK, JOIN -> styles.accent(text);
            case END, ACTION -> styles.success(text);
            case LOOP -> styles.warn(text);
            case SUB_WORKFLOW -> styles.gray(text);
        };
    }

    private void collectNextNodes(Node node, int level, Deque<NodeLevel> queue) {
        for (String target : NodeTargets.of(node)) {
            queue.add(new NodeLevel(target, level + 1));
        }
    }

    private record NodeLevel(String nodeId, int level) {}
}
