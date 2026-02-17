package io.hensu.cli.commands;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.*;
import java.util.stream.Collectors;
import picocli.CommandLine;

/// CLI command for validating workflow syntax and structure.
///
/// Parses the workflow and performs static analysis to detect issues:
/// - Syntax errors in Kotlin DSL
/// - Unreachable nodes (not connected to start node)
/// - Missing node references in transitions
///
/// ### Usage
/// ```bash
/// hensu validate [-d <working-dir>] <workflow-name>
/// ```
///
/// @see WorkflowCommand
@CommandLine.Command(name = "validate", description = "Validate workflow syntax")
class WorkflowValidateCommand extends WorkflowCommand {

    @CommandLine.Parameters(
            index = "0",
            description = "Workflow name (from workflows/ directory)",
            arity = "0..1")
    private String workflowName;

    @Override
    protected void execute() {
        try {
            Workflow workflow = getWorkflow(workflowName);

            System.out.println(" [OK] Workflow is valid!");
            System.out.println("   Name: " + workflow.getMetadata().getName());
            System.out.println("   Nodes: " + workflow.getNodes().size());
            System.out.println("   Agents: " + workflow.getAgents().size());

            List<String> unreachable = findUnreachableNodes(workflow);
            if (!unreachable.isEmpty()) {
                System.out.println(" [WARN] Unreachable nodes: " + String.join(", ", unreachable));
            }
        } catch (Exception e) {
            System.err.println(" [FAIL] Validation failed: " + e.getMessage());
        }
    }

    /// Finds nodes that are not reachable from the start node via any transition path.
    ///
    /// Performs breadth-first traversal from the start node following all transition types
    /// (success, failure, score). Nodes not visited are considered unreachable.
    ///
    /// @param workflow the workflow to analyze, not null
    /// @return list of unreachable node IDs (empty if all nodes are reachable)
    private List<String> findUnreachableNodes(Workflow workflow) {
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(workflow.getStartNode());

        while (!queue.isEmpty()) {
            String nodeId = queue.pollFirst();
            if (reachable.contains(nodeId)) continue;
            reachable.add(nodeId);

            Node node = workflow.getNodes().get(nodeId);
            if (node instanceof StandardNode standardNode) {
                for (TransitionRule rule : standardNode.getTransitionRules()) {
                    if (rule instanceof SuccessTransition st) queue.add(st.getTargetNode());
                    else if (rule instanceof FailureTransition ft)
                        queue.add(ft.getThenTargetNode());
                    else if (rule instanceof ScoreTransition sct) {
                        sct.getConditions().forEach(c -> queue.add(c.getTargetNode()));
                    }
                }
            }
        }

        return workflow.getNodes().keySet().stream()
                .filter(id -> !reachable.contains(id))
                .collect(Collectors.toList());
    }
}
