package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import java.util.List;
import java.util.Map;

/// Score-based transition rule that evaluates conditions against the agent-reported score.
///
/// Reads the `"score"` key from the execution context, which is populated by
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} from the agent's
/// JSON response (via `writes("score")` declared on the node).
///
/// @param conditions list of score conditions to evaluate in order, not null
/// @see ScoreCondition for condition matching logic
/// @see TransitionRule for transition evaluation contract
///
/// @implNote **Immutable after construction.** The conditions list is defensively copied.
public record ScoreTransition(List<ScoreCondition> conditions) implements TransitionRule {

    public ScoreTransition {
        conditions = List.copyOf(conditions);
    }

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        Double score = extractScore(state);
        if (score == null) {
            return null;
        }
        return conditions.stream()
                .filter(cn -> cn.matches(score))
                .findFirst()
                .map(ScoreCondition::targetNode)
                .orElse(null);
    }

    private Double extractScore(HensuState state) {
        Map<String, Object> context = state.getContext();
        if (context == null) return null;
        return parseScore(context.get("score"));
    }

    private Double parseScore(Object value) {
        return switch (value) {
            case null -> null;
            case Number n -> n.doubleValue();
            case String s -> {
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    public List<ScoreCondition> getConditions() {
        return conditions;
    }
}
