package io.hensu.core.workflow.node;

import io.hensu.core.workflow.transition.BreakRule;
import io.hensu.core.workflow.transition.TransitionRule;
import io.hensu.core.workflow.transition.TransitionTargets;
import java.util.ArrayList;
import java.util.List;

/// Enumerates every node ID a node can route control flow to, across all node types.
///
/// This is the single source of truth for graph traversal — used by reachability
/// analysis in {@code WorkflowValidateCommand} and by the visualization formats.
/// It layers node-specific successors on top of {@link TransitionTargets}, which only
/// understands transition rules:
///
/// - All nodes contribute their {@link Node#getTransitionRules() transition rule} targets.
/// - {@link ForkNode} additionally contributes its fork {@link ForkNode#getTargets() targets}.
/// - {@link LoopNode} additionally contributes its {@link BreakRule break-rule} targets.
///
/// A {@link JoinNode}'s await targets are deliberately excluded: they are predecessors that
/// feed into the join, not successors it routes to.
///
/// @see TransitionTargets for the rule-level target extraction this builds on
public final class NodeTargets {

    private NodeTargets() {}

    /// Returns every node ID the given node can transition to.
    ///
    /// @param node the node to inspect, not null
    /// @return list of successor node IDs (may be empty, never null)
    public static List<String> of(Node node) {
        List<String> targets = new ArrayList<>();
        for (TransitionRule rule : node.getTransitionRules()) {
            targets.addAll(TransitionTargets.of(rule));
        }
        switch (node) {
            case ForkNode fork -> targets.addAll(fork.getTargets());
            case LoopNode loop -> {
                if (loop.getBreakRules() != null) {
                    for (BreakRule rule : loop.getBreakRules()) {
                        targets.add(rule.getTargetNode());
                    }
                }
            }
            default -> {}
        }
        return targets;
    }
}
