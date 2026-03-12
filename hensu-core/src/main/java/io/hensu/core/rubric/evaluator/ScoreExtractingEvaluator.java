package io.hensu.core.rubric.evaluator;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.Criterion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Extracts the engine-managed `score` from context and accumulates per-criterion feedback.
///
/// After {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} runs,
/// the `score` key is present in context — extracted automatically as an engine variable
/// whenever the node has a {@link io.hensu.core.workflow.transition.ScoreTransition}.
/// This evaluator reads that single key — no guessing, no JSON parsing.
///
/// If the score is below `criterion.getMinScore()` and a `recommendation` engine variable
/// is present in context, the text is appended to the {@link #RECOMMENDATIONS_KEY} list
/// so {@link io.hensu.core.execution.pipeline.RubricPostProcessor} can assemble a
/// combined backtrack context update from all failing criteria.
///
/// ### Contracts
/// - **Precondition**: {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor}
///   has already run; `context.get("score")` is populated via the engine variable mechanism
/// - **Postcondition**: Returns 0.0 if result is failure or score is absent; otherwise
///   returns the parsed numeric score
///
/// @implNote **Immutable after construction.** Stateless; safe to share across Virtual Threads.
///
/// @see io.hensu.core.execution.pipeline.OutputExtractionPostProcessor for engine
/// variable extraction
/// @see io.hensu.core.execution.pipeline.RubricPostProcessor for evaluation orchestration
public final class ScoreExtractingEvaluator implements RubricEvaluator {

    private static final Logger logger = Logger.getLogger(ScoreExtractingEvaluator.class.getName());

    /// Internal context key under which per-criterion rubric feedback is accumulated.
    ///
    /// Value type: `List<String>` — one entry per criterion that fell below its threshold,
    /// prefixed with the criterion ID (e.g. `"[clarity] add more examples"`).
    /// Consumed exclusively by {@link io.hensu.core.execution.pipeline.RubricPostProcessor}
    /// to build auto-backtrack context updates. Not user-accessible via template variables.
    public static final String RECOMMENDATIONS_KEY = "_rubric_criterion_feedback";

    @Override
    public double evaluate(Criterion criterion, NodeResult result, Map<String, Object> context) {
        if (!result.isSuccess()) {
            return 0.0;
        }

        Object raw = context.get("score");
        Double score = parseNumber(raw);

        if (score == null) {
            logger.warning(
                    "No 'score' key in context for criterion '"
                            + criterion.getId()
                            + "'. Ensure the node has a ScoreTransition so the engine extracts"
                            + " 'score' automatically.");
            return 0.0;
        }

        if (score < criterion.getMinScore()) {
            Object recommendation = context.get("recommendation");
            if (recommendation instanceof String text && !text.isBlank()) {
                addRecommendation(context, criterion.getId(), text);
            }
        }

        return score;
    }

    private Double parseNumber(Object value) {
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

    private void addRecommendation(Map<String, Object> context, String criterionId, String text) {
        Object current = context.get(RECOMMENDATIONS_KEY);
        if (!(current instanceof List<?>)) {
            current = new ArrayList<String>();
            context.put(RECOMMENDATIONS_KEY, current);
        }
        List<String> recommendations = (List<String>) current;
        recommendations.add("[" + criterionId + "] " + text);
    }
}
