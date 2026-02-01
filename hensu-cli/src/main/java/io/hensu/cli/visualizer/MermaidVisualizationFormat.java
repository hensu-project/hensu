package io.hensu.cli.visualizer;

import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.*;
import jakarta.enterprise.context.ApplicationScoped;

/// Mermaid diagram format visualization for workflows.
///
/// Generates Mermaid flowchart syntax wrapped in Markdown code blocks. Output can be rendered
/// in GitHub/GitLab Markdown, documentation tools, or at [mermaid.live](https://mermaid.live).
///
/// ### Node Shape Mapping
/// - **StandardNode**: Rectangle with agent label
/// - **EndNode**: Stadium shape with exit status
/// - **LoopNode**: Diamond shape
/// - **ParallelNode**: Double rectangle
/// - **ForkNode**: Asymmetric shape (flag)
/// - **JoinNode**: Parallelogram
/// - **GenericNode**: Hexagon with executor type
/// - **ActionNode**: Trapezoid
///
/// ### Edge Styles
/// - **Solid arrow** (`-->`) - success transitions
/// - **Dashed arrow** (`-.->`) - failure/break transitions with labels
///
/// @implNote Thread-safe. Stateless rendering.
/// @see TextVisualizationFormat for ASCII output
@ApplicationScoped
public class MermaidVisualizationFormat implements VisualizationFormat {

    @Override
    public String getName() {
        return "mermaid";
    }

    @Override
    public String render(Workflow workflow) {
        StringBuilder sb = new StringBuilder();

        sb.append("```mermaid\n");
        sb.append("flowchart TD\n");

        // Add title as a subgraph
        sb.append("  subgraph ")
                .append(sanitizeId(workflow.getId()))
                .append("[\"")
                .append(workflow.getMetadata().getName())
                .append("\"]\n");

        // Define nodes with styling
        for (var entry : workflow.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            renderNode(sb, nodeId, node);
        }

        sb.append("  end\n\n");

        // Define edges (transitions)
        for (var entry : workflow.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            renderEdges(sb, nodeId, node);
        }

        sb.append("```\n");
        return sb.toString();
    }

    private void renderNode(StringBuilder sb, String nodeId, Node node) {
        String id = sanitizeId(nodeId);

        String shape =
                switch (node) {
                    case EndNode tn -> {
                        String label = nodeId + " (" + tn.getExitStatus() + ")";
                        yield id + "([\"" + label + "\"])";
                    }
                    case StandardNode sn -> {
                        String label =
                                sn.getAgentId() != null
                                        ? nodeId + "\\n[" + sn.getAgentId() + "]"
                                        : nodeId;
                        yield id + "[\"" + label + "\"]";
                    }
                    case LoopNode ignored -> {
                        String label = nodeId + " (loop)";
                        yield id + "{\"" + label + "\"}";
                    }
                    case ParallelNode ignored -> {
                        String label = nodeId + " (parallel)";
                        yield id + "[[\"" + label + "\"]]";
                    }
                    case ForkNode fn -> {
                        String label = nodeId + "\\n(fork: " + fn.getTargets().size() + ")";
                        yield id + ">\"" + label + "\"]";
                    }
                    case JoinNode jn -> {
                        String label = nodeId + "\\n(join: " + jn.getMergeStrategy() + ")";
                        yield id + "[\"" + label + "\"/]";
                    }
                    case GenericNode gn -> {
                        String label = nodeId + "\\n[" + gn.getExecutorType() + "]";
                        yield id + "{{\"" + label + "\"}}";
                    }
                    case ActionNode an -> {
                        String label = nodeId + "\\n(action: " + an.getActions().size() + ")";
                        yield id + "[/\"" + label + "\"/]";
                    }
                    default -> id + "[" + nodeId + "]";
                };

        sb.append("    ").append(shape).append("\n");
    }

    private void renderEdges(StringBuilder sb, String nodeId, Node node) {
        String fromId = sanitizeId(nodeId);

        if (node instanceof StandardNode standardNode) {
            renderTransitionRules(sb, fromId, standardNode.getTransitionRules());
        } else if (node instanceof LoopNode loopNode && loopNode.getBreakRules() != null) {
            for (BreakRule rule : loopNode.getBreakRules()) {
                String toId = sanitizeId(rule.getTargetNode());
                sb.append("  ").append(fromId).append(" -.->|break| ").append(toId).append("\n");
            }
        } else if (node instanceof ForkNode forkNode) {
            // Render edges to all fork targets
            for (String target : forkNode.getTargets()) {
                String toId = sanitizeId(target);
                sb.append("  ").append(fromId).append(" -->|fork| ").append(toId).append("\n");
            }
            // Render transition rules (onComplete)
            renderTransitionRules(sb, fromId, forkNode.getTransitionRules());
        } else if (node instanceof JoinNode joinNode) {
            // Render await connections (dashed lines showing dependencies)
            for (String target : joinNode.getAwaitTargets()) {
                String toId = sanitizeId(target);
                sb.append("  ").append(toId).append(" -.->|await| ").append(fromId).append("\n");
            }
            // Render transition rules
            renderTransitionRules(sb, fromId, joinNode.getTransitionRules());
        } else if (node instanceof GenericNode genericNode) {
            renderTransitionRules(sb, fromId, genericNode.getTransitionRules());
        } else if (node instanceof ParallelNode parallelNode) {
            renderTransitionRules(sb, fromId, parallelNode.getTransitionRules());
        } else if (node instanceof ActionNode actionNode) {
            renderTransitionRules(sb, fromId, actionNode.getTransitionRules());
        }
    }

    private void renderTransitionRules(
            StringBuilder sb, String fromId, java.util.List<TransitionRule> rules) {
        for (TransitionRule rule : rules) {
            if (rule instanceof SuccessTransition success) {
                String toId = sanitizeId(success.getTargetNode());
                sb.append("  ").append(fromId).append(" --> ").append(toId).append("\n");
            } else if (rule instanceof FailureTransition failure) {
                String toId = sanitizeId(failure.getThenTargetNode());
                String label =
                        failure.getRetryCount() > 0
                                ? "retry " + failure.getRetryCount()
                                : "failure";
                sb.append("  ")
                        .append(fromId)
                        .append(" -.->|")
                        .append(label)
                        .append("| ")
                        .append(toId)
                        .append("\n");
            } else if (rule instanceof ScoreTransition score) {
                for (ScoreCondition cond : score.getConditions()) {
                    String toId = sanitizeId(cond.getTargetNode());
                    String label = "score " + cond.getOperator() + " " + cond.getValue();
                    sb.append("  ")
                            .append(fromId)
                            .append(" -->|")
                            .append(label)
                            .append("| ")
                            .append(toId)
                            .append("\n");
                }
            }
        }
    }

    private String sanitizeId(String id) {
        String sanitized = id.replaceAll("[^a-zA-Z0-9_]", "_");
        // Prefix reserved Mermaid keywords
        if (isReservedKeyword(sanitized)) {
            return "node_" + sanitized;
        }
        return sanitized;
    }

    private boolean isReservedKeyword(String id) {
        return switch (id.toLowerCase()) {
            case "end",
                    "subgraph",
                    "graph",
                    "flowchart",
                    "direction",
                    "click",
                    "style",
                    "classdef",
                    "class",
                    "linkstyle" ->
                    true;
            default -> false;
        };
    }
}
