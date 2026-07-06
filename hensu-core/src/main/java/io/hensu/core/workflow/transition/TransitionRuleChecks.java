package io.hensu.core.workflow.transition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Build-time structural checks over a node's transition rule list.
///
/// Lives in the transition package so rule-shape inspection stays inside the
/// sealed hierarchy's home – DSL builders and graph validation consume these
/// checks instead of dispatching on rule types themselves.
///
/// @see TransitionTargets for reachability extraction
public final class TransitionRuleChecks {

    private TransitionRuleChecks() {}

    /// Returns every {@link BoundedTransition} namespace that appears more than once
    /// in the given rule list. Two bounded rules sharing a namespace on one node
    /// silently share a retry counter – a build error in the DSL.
    ///
    /// @param rules the node's transition rules, not null
    /// @return duplicated namespace names in first-seen order (may be empty, never null)
    public static List<String> duplicateBoundedNamespaces(List<TransitionRule> rules) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (TransitionRule rule : rules) {
            if (rule instanceof BoundedTransition bt
                    && !seen.add(bt.namespace())
                    && reported.add(bt.namespace())) {
                duplicates.add(bt.namespace());
            }
        }
        return duplicates;
    }

    /// Detects a bounded backtracking arm ordered before a `ConditionTransition` /
    /// `ScoreTransition` exit arm targeting a different node. Rule ordering is
    /// load-bearing (first match wins), so the exit arm cannot fire until the
    /// budget is exhausted – usually a mistake, occasionally deliberate, hence a
    /// warning rather than an error.
    ///
    /// @param rules the node's transition rules in declaration order, not null
    /// @param nodeId the id of the node owning the rules, not null
    /// @return warning message describing the shadowed exit arm, or empty
    public static Optional<String> boundedBeforeExitWarning(
            List<TransitionRule> rules, String nodeId) {
        boolean boundedSeen = false;
        for (TransitionRule rule : rules) {
            if (rule instanceof BoundedTransition) {
                boundedSeen = true;
                continue;
            }
            if (boundedSeen
                    && (rule instanceof ConditionTransition || rule instanceof ScoreTransition)) {
                boolean exitsElsewhere =
                        TransitionTargets.of(rule).stream().anyMatch(t -> !t.equals(nodeId));
                if (exitsElsewhere) {
                    return Optional.of(
                            "Node '"
                                    + nodeId
                                    + "': an exit arm is declared after a bounded retry arm."
                                    + " Rules evaluate in order, so the exit arm cannot fire"
                                    + " until the retry budget is exhausted. Declare the exit"
                                    + " arm first unless budget-first routing is intended.");
                }
            }
        }
        return Optional.empty();
    }
}
