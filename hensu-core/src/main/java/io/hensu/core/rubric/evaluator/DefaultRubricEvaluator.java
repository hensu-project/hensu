package io.hensu.core.rubric.evaluator;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.util.JsonUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Self-evaluation based rubric evaluator.
///
/// Extracts self-reported scores and recommendations from LLM agent output. When an agent
/// evaluates its own work, it should return JSON with
///
/// ### Example
/// {@snippet lang=JSON:
/// {
///   "score": 75,
///   "recommendation": "To improve, add more specific examples..."
/// }
/// }
///
/// If score is below threshold, the recommendation is stored in context and will be injected into
/// the prompt when backtracking.
public final class DefaultRubricEvaluator implements RubricEvaluator {

    private static final Logger logger = Logger.getLogger(DefaultRubricEvaluator.class.getName());

    // Context key for storing recommendations
    public static final String RECOMMENDATIONS_KEY = "self_evaluation_recommendations";

    // Keys to look for in agent output
    private static final String[] SCORE_KEYS = {
        "score", "self_score", "quality_score", "final_score"
    };
    private static final String[] RECOMMENDATION_KEYS = {
        "recommendation", "recommendations", "improvement", "suggestion", "feedback"
    };

    public DefaultRubricEvaluator() {}

    @Override
    public double evaluate(Criterion criterion, NodeResult result, Map<String, Object> context) {

        if (!result.isSuccess()) {
            addRecommendation(
                    context,
                    criterion.getId(),
                    "Task execution failed. Retry with corrected approach.");
            return 0.0;
        }

        String output = result.getOutput() != null ? result.getOutput().toString() : "";

        // Extract self-reported score from agent output
        Double selfScore = extractFromOutput(output, context, SCORE_KEYS, true);

        if (selfScore != null) {
            logger.info("Self-evaluated score for " + criterion.getId() + ": " + selfScore);

            // If score is low, extract and store recommendation
            if (selfScore < criterion.getMinScore()) {
                String recommendation =
                        extractFromOutput(output, context, RECOMMENDATION_KEYS, false);
                if (recommendation != null && !recommendation.isBlank()) {
                    addRecommendation(context, criterion.getId(), recommendation);
                    logger.info(
                            "Stored recommendation for "
                                    + criterion.getId()
                                    + ": "
                                    + recommendation);
                }
            }

            return selfScore;
        }

        // Fallback to rule-based evaluation if no self-score
        String logic = criterion.getEvaluationLogic();
        if (logic == null || logic.isBlank()) {
            return 100.0;
        }

        return evaluateLogic(logic, result, context);
    }

    /// Extract value from context or JSON output. Uses a unified approach for both score and
    /// recommendation extraction.
    ///
    /// @param output The agent's output text
    /// @param context The execution context
    /// @param keys Keys to search for
    /// @param asNumber If true, extract as Double; if false, extract as String
    /// @return The extracted value, or null if not found
    private <T> T extractFromOutput(
            String output, Map<String, Object> context, String[] keys, boolean asNumber) {
        // Check context first (from outputParams extraction)
        for (String key : keys) {
            Object value = context.get(key);
            if (asNumber) {
                Double score = parseNumber(value);
                if (score != null) {
                    return (T) score;
                }
            } else {
                if (value instanceof String s && !s.isBlank()) {
                    return (T) s;
                }
            }
        }

        // Extract from JSON in output
        String json = JsonUtil.extractJsonFromOutput(output);
        if (json != null) {
            for (String key : keys) {
                if (asNumber) {
                    Double score = JsonUtil.extractJsonNumber(json, key);
                    if (score != null) {
                        return (T) score;
                    }
                } else {
                    String value = JsonUtil.extractJsonField(json, key);
                    if (value != null && !value.isBlank()) {
                        return (T) value;
                    }
                }
            }
        }

        return null;
    }

    /// Add recommendation to context for injection into backtrack prompt.
    private void addRecommendation(
            Map<String, Object> context, String criterionId, String recommendation) {
        List<String> recommendations =
                (List<String>)
                        context.computeIfAbsent(RECOMMENDATIONS_KEY, _ -> new ArrayList<String>());
        recommendations.add("[" + criterionId + "] " + recommendation);
    }

    private Double parseNumber(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case Number n -> {
                return n.doubleValue();
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

    // --- Fallback rule-based evaluation ---

    private double evaluateLogic(String logic, NodeResult result, Map<String, Object> context) {
        if (logic.contains("=")) {
            return evaluateEquality(logic, context);
        } else if (logic.contains(">")) {
            return evaluateComparison(logic, context, true);
        } else if (logic.contains("<")) {
            return evaluateComparison(logic, context, false);
        }
        return 100.0;
    }

    private double evaluateEquality(String logic, Map<String, Object> context) {
        String[] parts = logic.split("=");
        if (parts.length != 2) {
            return 0.0;
        }

        String key = parts[0].trim();
        String expectedValue = parts[1].trim();

        Object actualValue = context.get(key);
        if (actualValue == null) {
            return 0.0;
        }

        return actualValue.toString().equals(expectedValue) ? 100.0 : 0.0;
    }

    private double evaluateComparison(
            String logic, Map<String, Object> context, boolean greaterThan) {
        String[] parts = logic.split(greaterThan ? ">" : "<");
        if (parts.length != 2) {
            return 0.0;
        }

        String key = parts[0].trim();
        double threshold;
        try {
            threshold = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }

        Object value = context.get(key);
        if (!(value instanceof Number)) {
            return 0.0;
        }

        double actualValue = ((Number) value).doubleValue();
        boolean result = greaterThan ? actualValue > threshold : actualValue < threshold;
        return result ? 100.0 : 0.0;
    }
}
