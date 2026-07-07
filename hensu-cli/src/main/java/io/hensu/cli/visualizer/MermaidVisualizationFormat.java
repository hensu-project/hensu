package io.hensu.cli.visualizer;

import io.hensu.core.review.ReviewMode;
import io.hensu.core.rubric.model.ComparisonOperator;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.*;
import io.hensu.core.workflow.transition.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Mermaid diagram format visualization for workflows.
///
/// Generates Mermaid flowchart syntax wrapped in Markdown code blocks. Output can be rendered
/// in GitHub/GitLab Markdown, documentation tools, or at [mermaid.live](https://mermaid.live).
///
/// ### Node Shape Mapping
/// - **StandardNode**: Stadium (pill) shape with agent label
/// - **EndNode**: Stadium shape with exit status
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
        return render(workflow, Map.of());
    }

    private static final String NODE_STYLE =
            "fill:#2c2c2e, stroke:#48484a, color:#ebebf5, stroke-width:1px";
    private static final String SUBGRAPH_STYLE =
            "fill:#2c2c2e, stroke:#3a3a3c, color:#ebebf5, stroke-width:1px";
    private static final String NESTED_SUBGRAPH_STYLE =
            "fill:#3a3a3c, stroke:#48484a, color:#ebebf5, stroke-width:1px";
    private static final String LINK_STYLE = "stroke:#0A84FF, stroke-width:1px";

    @Override
    public String render(Workflow workflow, Map<String, Workflow> subWorkflows) {
        StringBuilder sb = new StringBuilder();
        List<String> nodeIds = new ArrayList<>();
        List<String> nestedSubgraphIds = new ArrayList<>();

        sb.append("```mermaid\n");
        sb.append("flowchart TD\n");

        List<Map.Entry<String, Node>> ordered = bfsOrder(workflow);
        String rootSubgraphId = sanitizeId(workflow.getId());

        sb.append("  subgraph ")
                .append(rootSubgraphId)
                .append("[\"")
                .append(escapeLabel(workflow.getMetadata().getName()))
                .append("\"]\n");

        for (var entry : ordered) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            String id = sanitizeId(nodeId);
            nodeIds.add(id);
            renderNode(sb, nodeId, node);

            if (node instanceof SubWorkflowNode swn) {
                Workflow sub = subWorkflows.get(swn.getWorkflowId());
                if (sub != null) {
                    renderInlinedSubWorkflow(sb, swn, sub, nodeIds, nestedSubgraphIds);
                }
            } else if (node instanceof ParallelNode pn) {
                renderParallelBranches(sb, id, nodeId, pn, nodeIds);
            }
        }

        sb.append("  end\n\n");

        for (var entry : ordered) {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            renderEdges(sb, nodeId, node);

            if (node instanceof SubWorkflowNode swn) {
                Workflow sub = subWorkflows.get(swn.getWorkflowId());
                if (sub != null) {
                    String fromId = sanitizeId(nodeId);
                    String startId = namespacedId(swn.getWorkflowId(), sub.getStartNode());
                    sb.append("  ")
                            .append(fromId)
                            .append(" -->|sub| ")
                            .append(startId)
                            .append("\n");

                    for (var subEntry : bfsOrder(sub)) {
                        renderEdges(
                                sb,
                                namespacedId(swn.getWorkflowId(), subEntry.getKey()),
                                subEntry.getValue(),
                                swn.getWorkflowId());
                    }
                }
            }
        }

        sb.append("\n");
        renderStyles(sb, rootSubgraphId, nestedSubgraphIds, nodeIds);

        sb.append("```\n");
        return sb.toString();
    }

    private void renderNode(StringBuilder sb, String nodeId, Node node) {
        renderNode(sb, sanitizeId(nodeId), nodeId, node);
    }

    private void renderInlinedSubWorkflow(
            StringBuilder sb,
            SubWorkflowNode swn,
            Workflow sub,
            List<String> nodeIds,
            List<String> nestedSubgraphIds) {
        String subId = sanitizeId(swn.getWorkflowId());
        nestedSubgraphIds.add(subId);
        sb.append("    subgraph ")
                .append(subId)
                .append("[\"")
                .append(escapeLabel(swn.getWorkflowId()))
                .append("\"]\n");
        for (var entry : bfsOrder(sub)) {
            String nsId = namespacedId(swn.getWorkflowId(), entry.getKey());
            nodeIds.add(nsId);
            renderNode(sb, nsId, entry.getKey(), entry.getValue());
        }
        sb.append("    end\n");
    }

    private void renderParallelBranches(
            StringBuilder sb,
            String prefixId,
            String displayId,
            ParallelNode pn,
            List<String> nodeIds) {
        for (var branch : pn.getBranches()) {
            String branchId = prefixId + "_" + sanitizeId(branch.getId());
            nodeIds.add(branchId);
            String label =
                    escapeLabel(branch.getId()) + "\\n[" + escapeLabel(branch.getAgentId()) + "]";
            sb.append("    ")
                    .append(branchId)
                    .append("([\"")
                    .append(label)
                    .append("\"])")
                    .append("\n");
        }
        String joinId = prefixId + "___join";
        nodeIds.add(joinId);
        String joinLabel =
                escapeLabel(displayId)
                        + "\\n(join"
                        + (pn.getConsensusConfig() != null
                                ? ": " + pn.getConsensusConfig().getStrategy()
                                : "")
                        + ")";
        sb.append("    ").append(joinId).append("[\"").append(joinLabel).append("\"]").append("\n");
    }

    private void renderNode(StringBuilder sb, String id, String displayId, Node node) {
        String safeDisplayId = escapeLabel(displayId);
        String shape =
                switch (node) {
                    case EndNode tn -> {
                        String label = safeDisplayId + " (" + tn.getExitStatus() + ")";
                        yield id + "([\"" + label + "\"])";
                    }
                    case StandardNode sn -> {
                        var lb = new StringBuilder(safeDisplayId);
                        if (sn.getAgentId() != null) {
                            lb.append("\\n[").append(escapeLabel(sn.getAgentId())).append("]");
                        }
                        if (sn.getRubric() != null) {
                            lb.append("\\n rubric: ")
                                    .append(sn.getRubric().getCriteria().size())
                                    .append(" criteria");
                        }
                        if (sn.hasPlanningEnabled()) {
                            lb.append("\\n planning: ").append(sn.getPlanningConfig().mode());
                        }
                        if (sn.getReviewConfig() != null
                                && sn.getReviewConfig().getMode() != ReviewMode.DISABLED) {
                            lb.append("\\n review: ").append(sn.getReviewConfig().getMode());
                        }
                        yield id + "([\"" + lb + "\"])";
                    }
                    case ParallelNode pn -> {
                        String label =
                                safeDisplayId + "\\n(parallel: " + pn.getBranches().length + ")";
                        yield id + ">\"" + label + "\"]";
                    }
                    case ForkNode fn -> {
                        String label = safeDisplayId + "\\n(fork: " + fn.getTargets().size() + ")";
                        yield id + ">\"" + label + "\"]";
                    }
                    case JoinNode jn -> {
                        String label = safeDisplayId + "\\n(join: " + jn.getMergeStrategy() + ")";
                        yield id + "[\"" + label + "\"]";
                    }
                    case GenericNode gn -> {
                        String label =
                                safeDisplayId + "\\n[" + escapeLabel(gn.getExecutorType()) + "]";
                        yield id + "{{\"" + label + "\"}}";
                    }
                    case ActionNode an -> {
                        String label =
                                safeDisplayId + "\\n(action: " + an.getActions().size() + ")";
                        yield id + "[/\"" + label + "\"/]";
                    }
                    case SubWorkflowNode swn -> {
                        String label =
                                safeDisplayId
                                        + "\\n[sub: "
                                        + escapeLabel(swn.getWorkflowId())
                                        + "]";
                        yield id + "[(\"" + label + "\")]";
                    }
                    default -> id + "[" + safeDisplayId + "]";
                };

        sb.append("    ").append(shape).append("\n");
    }

    private static void renderStyles(
            StringBuilder sb,
            String rootSubgraphId,
            List<String> nestedSubgraphIds,
            List<String> nodeIds) {
        sb.append("  style ")
                .append(rootSubgraphId)
                .append(" ")
                .append(SUBGRAPH_STYLE)
                .append("\n");
        for (String id : nestedSubgraphIds) {
            sb.append("  style ").append(id).append(" ").append(NESTED_SUBGRAPH_STYLE).append("\n");
        }
        for (String id : nodeIds) {
            sb.append("  style ").append(id).append(" ").append(NODE_STYLE).append("\n");
        }
        sb.append("  linkStyle default ").append(LINK_STYLE).append("\n");
    }

    private static List<Map.Entry<String, Node>> bfsOrder(Workflow workflow) {
        LinkedHashMap<String, Node> ordered = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(workflow.getStartNode());

        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            if (!visited.add(nodeId)) continue;

            Node node = workflow.getNodes().get(nodeId);
            if (node == null) continue;
            ordered.put(nodeId, node);

            queue.addAll(NodeTargets.of(node));
        }

        return new ArrayList<>(ordered.entrySet());
    }

    private static String namespacedId(String workflowId, String nodeId) {
        return sanitizeId(workflowId) + "__" + sanitizeId(nodeId);
    }

    private void renderEdges(StringBuilder sb, String nodeId, Node node, String workflowPrefix) {
        renderEdgesInternal(sb, nodeId, node, workflowPrefix);
    }

    private void renderEdges(StringBuilder sb, String nodeId, Node node) {
        String fromId = sanitizeId(nodeId);
        renderEdgesInternal(sb, fromId, node, null);
    }

    private void renderEdgesInternal(
            StringBuilder sb, String fromId, Node node, String workflowPrefix) {

        switch (node) {
            case ForkNode forkNode -> {
                for (String target : forkNode.getTargets()) {
                    String toId = resolveTargetId(target, workflowPrefix);
                    sb.append("  ").append(fromId).append(" -->|fork| ").append(toId).append("\n");
                }
                renderTransitionRules(sb, fromId, forkNode.getTransitionRules(), workflowPrefix);
            }
            case JoinNode joinNode -> {
                for (String target : joinNode.getAwaitTargets()) {
                    String toId = resolveTargetId(target, workflowPrefix);
                    sb.append("  ")
                            .append(toId)
                            .append(" -.->|await| ")
                            .append(fromId)
                            .append("\n");
                }
                renderTransitionRules(sb, fromId, joinNode.getTransitionRules(), workflowPrefix);
            }
            case ParallelNode pn -> {
                for (var branch : pn.getBranches()) {
                    String branchId = fromId + "_" + sanitizeId(branch.getId());
                    sb.append("  ")
                            .append(fromId)
                            .append(" -->|branch| ")
                            .append(branchId)
                            .append("\n");
                }
                String joinId = fromId + "___join";
                for (var branch : pn.getBranches()) {
                    String branchId = fromId + "_" + sanitizeId(branch.getId());
                    sb.append("  ").append(branchId).append(" --> ").append(joinId).append("\n");
                }
                renderTransitionRules(sb, joinId, pn.getTransitionRules(), workflowPrefix);
            }
            default -> renderTransitionRules(sb, fromId, node.getTransitionRules(), workflowPrefix);
        }
    }

    private void renderTransitionRules(
            StringBuilder sb, String fromId, List<TransitionRule> rules, String workflowPrefix) {
        for (TransitionRule rule : rules) {
            if (rule instanceof BoundedTransition bounded) {
                renderBoundedTransition(sb, fromId, bounded, workflowPrefix);
            } else if (rule instanceof SuccessTransition success) {
                String toId = resolveTargetId(success.getTargetNode(), workflowPrefix);
                sb.append("  ").append(fromId).append(" --> ").append(toId).append("\n");
            } else if (rule instanceof FailureTransition(String node)) {
                if (node != null) {
                    String toId = resolveTargetId(node, workflowPrefix);
                    sb.append("  ")
                            .append(fromId)
                            .append(" -.->|failure| ")
                            .append(toId)
                            .append("\n");
                }
            } else if (rule instanceof NoConsensusTransition(String node, boolean fb)) {
                String toId = resolveTargetId(node, workflowPrefix);
                String label = fb ? "no consensus · fb" : "no consensus";
                sb.append("  ")
                        .append(fromId)
                        .append(" -.->|")
                        .append(label)
                        .append("| ")
                        .append(toId)
                        .append("\n");
            } else if (rule instanceof ScoreTransition score) {
                for (ScoreCondition cond : score.getConditions()) {
                    String toId = resolveTargetId(cond.getTargetNode(), workflowPrefix);
                    String label =
                            cond.getOperator() == ComparisonOperator.RANGE && cond.range() != null
                                    ? "score " + cond.range().start() + "–" + cond.range().end()
                                    : "score " + cond.getOperator() + " " + cond.getValue();
                    sb.append("  ")
                            .append(fromId)
                            .append(" -->|")
                            .append(label)
                            .append("| ")
                            .append(toId)
                            .append("\n");
                }
            } else if (rule
                    instanceof
                    ApprovalTransition(boolean expected, String targetNode, boolean fb)) {
                String toId = resolveTargetId(targetNode, workflowPrefix);
                String base = expected ? "approved" : "rejected";
                String label = fb ? base + " · fb" : base;
                sb.append("  ")
                        .append(fromId)
                        .append(" -->|")
                        .append(label)
                        .append("| ")
                        .append(toId)
                        .append("\n");
            } else if (rule instanceof ConditionTransition condition) {
                String toId = resolveTargetId(condition.targetNode(), workflowPrefix);
                sb.append("  ")
                        .append(fromId)
                        .append(" -->|")
                        .append(escapeLabel(conditionLabel(condition)))
                        .append("| ")
                        .append(toId)
                        .append("\n");
            } else if (rule instanceof AlwaysTransition(String targetNode, boolean fb)) {
                String toId = resolveTargetId(targetNode, workflowPrefix);
                String label = fb ? "otherwise · fb" : "otherwise";
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

    private static String conditionLabel(ConditionTransition condition) {
        return condition.variable() + " " + condition.condition().describe();
    }

    private void renderBoundedTransition(
            StringBuilder sb, String fromId, BoundedTransition bounded, String workflowPrefix) {
        TransitionRule inner = bounded.trigger();
        String budgetLabel = " ≤" + bounded.budget();

        // Inner target edge with budget annotation
        if (inner instanceof FailureTransition(String node)) {
            if (node != null) {
                String toId = resolveTargetId(node, workflowPrefix);
                sb.append("  ")
                        .append(fromId)
                        .append(" -.->|retry")
                        .append(budgetLabel)
                        .append("| ")
                        .append(toId)
                        .append("\n");
            } else {
                // Self-loop retry
                sb.append("  ")
                        .append(fromId)
                        .append(" -.->|retry")
                        .append(budgetLabel)
                        .append("| ")
                        .append(fromId)
                        .append("\n");
            }
        } else if (inner instanceof NoConsensusTransition(String node, boolean fb)) {
            String toId = resolveTargetId(node, workflowPrefix);
            String fbSuffix = fb ? " · fb" : "";
            sb.append("  ")
                    .append(fromId)
                    .append(" -.->|no consensus · revise")
                    .append(budgetLabel)
                    .append(fbSuffix)
                    .append("| ")
                    .append(toId)
                    .append("\n");
        } else if (inner
                instanceof ApprovalTransition(boolean expected, String targetNode, boolean fb)) {
            String toId = resolveTargetId(targetNode, workflowPrefix);
            String prefix = expected ? "approved" : "rejected";
            String fbSuffix = fb ? " · fb" : "";
            sb.append("  ")
                    .append(fromId)
                    .append(" -->|")
                    .append(prefix)
                    .append(" · revise")
                    .append(budgetLabel)
                    .append(fbSuffix)
                    .append("| ")
                    .append(toId)
                    .append("\n");
        } else if (inner instanceof ScoreTransition score) {
            for (ScoreCondition cond : score.getConditions()) {
                String toId = resolveTargetId(cond.getTargetNode(), workflowPrefix);
                String condLabel =
                        cond.getOperator() == ComparisonOperator.RANGE && cond.range() != null
                                ? "score " + cond.range().start() + "–" + cond.range().end()
                                : "score " + cond.getOperator() + " " + cond.getValue();
                sb.append("  ")
                        .append(fromId)
                        .append(" -->|")
                        .append(condLabel)
                        .append(" · revise")
                        .append(budgetLabel)
                        .append("| ")
                        .append(toId)
                        .append("\n");
            }
        } else if (inner instanceof ConditionTransition condition) {
            String toId = resolveTargetId(condition.targetNode(), workflowPrefix);
            sb.append("  ")
                    .append(fromId)
                    .append(" -->|")
                    .append(escapeLabel(conditionLabel(condition)))
                    .append(" · revise")
                    .append(budgetLabel)
                    .append("| ")
                    .append(toId)
                    .append("\n");
        } else if (inner instanceof AlwaysTransition(String targetNode, boolean _)) {
            String toId = resolveTargetId(targetNode, workflowPrefix);
            sb.append("  ")
                    .append(fromId)
                    .append(" -->|otherwise · revise")
                    .append(budgetLabel)
                    .append("| ")
                    .append(toId)
                    .append("\n");
        }

        // Escalation edge
        String escalationId = resolveTargetId(bounded.otherwise(), workflowPrefix);
        sb.append("  ")
                .append(fromId)
                .append(" -.->|budget exhausted| ")
                .append(escalationId)
                .append("\n");
    }

    private static String resolveTargetId(String nodeId, String workflowPrefix) {
        if (workflowPrefix != null) {
            return namespacedId(workflowPrefix, nodeId);
        }
        return sanitizeId(nodeId);
    }

    private static String sanitizeId(String id) {
        String sanitized = id.replaceAll("[^a-zA-Z0-9_]", "_");
        // Prefix reserved Mermaid keywords
        if (isReservedKeyword(sanitized)) {
            return "node_" + sanitized;
        }
        return sanitized;
    }

    private static String escapeLabel(String label) {
        return label.replace("\"", "&quot;");
    }

    private static boolean isReservedKeyword(String id) {
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
