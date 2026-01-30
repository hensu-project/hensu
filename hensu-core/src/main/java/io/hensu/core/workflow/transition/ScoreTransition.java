package io.hensu.core.workflow.transition;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.ScoreCondition;
import io.hensu.core.state.HensuState;
import java.util.List;
import java.util.Map;

/// Score-based transition rule that evaluates conditions against rubric or self-reported scores.
///
/// Supports two evaluation modes:
/// 1. **Rubric evaluation**: Uses score from independent LLM-based rubric evaluation
/// 2. **Self-reported score**: Extracts score from agent output via `outputParams`
///
/// Score keys checked in context (in priority order):
/// `score`, `final_score`, `quality_score`, `evaluation_score`
///
/// @param conditions list of score conditions to evaluate in order, not null
/// @see ScoreCondition for condition matching logic
/// @see TransitionRule for transition evaluation contract
public record ScoreTransition(List<ScoreCondition> conditions) implements TransitionRule {

    private static final String[] SCORE_KEYS = {
        "score", "final_score", "quality_score", "evaluation_score"
    };

    @Override
    public String evaluate(HensuState state, NodeResult result) {
        Double score = extractScore(state);

        if (score == null) {
            // No score available, cannot evaluate
            return null;
        }

        return conditions.stream()
                .filter(cn -> cn.matches(score))
                .findFirst()
                .map(ScoreCondition::targetNode)
                .orElse(null);
    }

    /// Extracts score from rubric evaluation or self-reported context values.
    ///
    /// @param state current workflow state, not null
    /// @return extracted score, or null if no score available
    private Double extractScore(HensuState state) {
        // Priority 1: Rubric evaluation score
        RubricEvaluation rubricEval = state.getRubricEvaluation();
        if (rubricEval != null) {
            return rubricEval.getScore();
        }

        // Priority 2: Self-reported score from context
        Map<String, Object> context = state.getContext();
        if (context != null) {
            for (String key : SCORE_KEYS) {
                Object value = context.get(key);
                Double score = parseScore(value);
                if (score != null) {
                    return score;
                }
            }
        }

        return null;
    }

    private Double parseScore(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case Number number -> {
                return number.doubleValue();
            }
            case String s -> {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            default -> {}
        }
        return null;
    }

    public List<ScoreCondition> getConditions() {
        return conditions;
    }
}
