package io.hensu.core.workflow.transition;

import io.hensu.core.rubric.model.ScoreCondition;
import java.util.List;
import java.util.stream.Stream;

/// Extracts every node ID a transition rule can route to, including decorator escalation targets.
///
/// This is the single source of truth for reachability analysis — used by
/// {@code WorkflowValidator}, {@code WorkflowValidateCommand}, and the visualization formats.
/// Null targets (the self-loop form of {@link FailureTransition}) are omitted.
///
/// @see BoundedTransition for rules that have both inner targets and an escalation target
public final class TransitionTargets {

    private TransitionTargets() {}

    /// Returns every node ID the given rule can transition to.
    ///
    /// @param rule the transition rule to inspect, not null
    /// @return list of reachable node IDs (may be empty, never null)
    public static List<String> of(TransitionRule rule) {
        return switch (rule) {
            case SuccessTransition s -> List.of(s.targetNode());
            case FailureTransition f ->
                    f.targetNode() != null ? List.of(f.targetNode()) : List.of();
            case ScoreTransition s ->
                    s.conditions().stream().map(ScoreCondition::targetNode).toList();
            case ApprovalTransition a -> List.of(a.targetNode());
            case NoConsensusTransition n -> List.of(n.targetNode());
            case BoundedTransition b ->
                    Stream.concat(of(b.inner()).stream(), Stream.of(b.otherwise())).toList();
            case AlwaysTransition a -> List.of(a.targetNode());
            case ConditionTransition c -> List.of(c.targetNode());
            case RubricFailTransition _ -> List.of();
        };
    }
}
