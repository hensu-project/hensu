package io.hensu.core.rubric.evaluator;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.Criterion;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// LLM-based rubric evaluator that uses an AI agent to evaluate content against criteria and
/// provide meaningful scores.
///
/// ### This evaluator
/// 1. Sends the agent output + criterion to an LLM
/// 2. Gets a score (0-100) with reasoning
/// 3. Returns partial scores, not just binary 100/0
public final class LLMRubricEvaluator implements RubricEvaluator {

    private static final Logger logger = Logger.getLogger(LLMRubricEvaluator.class.getName());
    private static final Pattern SCORE_PATTERN =
            Pattern.compile("\"?score\"?\\s*[=:]\\s*(\\d+(?:\\.\\d+)?)");

    private final AgentRegistry agentRegistry;
    private final String evaluatorAgentId;

    /// Create an LLM evaluator using a specific agent for evaluation.
    ///
    /// @param agentRegistry Registry to get the evaluator agent
    /// @param evaluatorAgentId ID of the agent to use for evaluation (e.g., "evaluator")
    public LLMRubricEvaluator(AgentRegistry agentRegistry, String evaluatorAgentId) {
        this.agentRegistry = agentRegistry;
        this.evaluatorAgentId = evaluatorAgentId;
    }

    /// Create an LLM evaluator using the default "evaluator" agent.
    public LLMRubricEvaluator(AgentRegistry agentRegistry) {
        this(agentRegistry, "evaluator");
    }

    @Override
    public double evaluate(Criterion criterion, NodeResult result, Map<String, Object> context) {

        // If node failed, score is 0
        if (!result.isSuccess()) {
            return 0.0;
        }

        // Get the agent's output to evaluate
        String output = result.getOutput() != null ? result.getOutput().toString() : "";
        if (output.isBlank()) {
            logger.warning("No output to evaluate for criterion: " + criterion.getId());
            return 0.0;
        }

        // Check if we have an evaluator agent
        if (!agentRegistry.hasAgent(evaluatorAgentId)) {
            logger.warning(
                    "Evaluator agent '" + evaluatorAgentId + "' not found, using default scoring");
            return evaluateWithoutLLM(criterion, output, context);
        }

        try {
            return evaluateWithLLM(criterion, output, context);
        } catch (Exception e) {
            logger.warning("LLM evaluation failed, falling back to default: " + e.getMessage());
            return evaluateWithoutLLM(criterion, output, context);
        }
    }

    private double evaluateWithLLM(
            Criterion criterion, String output, Map<String, Object> context) {

        Agent evaluator =
                agentRegistry
                        .getAgent(evaluatorAgentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Evaluator agent not found: " + evaluatorAgentId));

        String prompt = buildEvaluationPrompt(criterion, output, context);

        AgentResponse response = evaluator.execute(prompt, context);

        if (!response.isSuccess()) {
            logger.warning("Evaluator agent failed: " + response.getOutput());
            return 50.0; // Neutral score on failure
        }

        return parseScore(response.getOutput());
    }

    private String buildEvaluationPrompt(
            Criterion criterion, String output, Map<String, Object> context) {

        return """
            You are an expert evaluator. Evaluate the following content against the given criterion.

            ## Criterion
            - Name: %s
            - Description: %s
            - Evaluation Logic: %s

            ## Content to Evaluate
            %s

            ## Instructions
            1. Carefully read the criterion and the content
            2. Evaluate how well the content meets the criterion
            3. Provide a score from 0 to 100:
               - 0-20: Does not meet the criterion at all
               - 21-40: Barely meets the criterion, significant issues
               - 41-60: Partially meets the criterion, some issues
               - 61-80: Mostly meets the criterion, minor issues
               - 81-100: Fully meets the criterion

            ## Response Format
            Respond with JSON only:
            {
                "score": <number 0-100>,
                "reasoning": "<brief explanation>"
            }
            """
                .formatted(
                        criterion.getName(),
                        criterion.getDescription(),
                        criterion.getEvaluationLogic() != null
                                ? criterion.getEvaluationLogic()
                                : "General quality assessment",
                        output);
    }

    private double parseScore(String response) {
        Matcher matcher = SCORE_PATTERN.matcher(response);
        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                // Clamp to 0-100
                return Math.max(0, Math.min(100, score));
            } catch (NumberFormatException e) {
                logger.warning("Failed to parse score from response: " + response);
            }
        }

        // Try to infer from keywords if no explicit score
        String lower = response.toLowerCase();
        if (lower.contains("excellent") || lower.contains("perfect")) {
            return 95.0;
        } else if (lower.contains("good") || lower.contains("well")) {
            return 80.0;
        } else if (lower.contains("adequate") || lower.contains("acceptable")) {
            return 65.0;
        } else if (lower.contains("poor") || lower.contains("lacking")) {
            return 35.0;
        } else if (lower.contains("fail") || lower.contains("missing")) {
            return 15.0;
        }

        // Default neutral score
        return 50.0;
    }

    /// Fallback evaluation without LLM - more intelligent than binary. Uses heuristics based on
    /// output characteristics.
    private double evaluateWithoutLLM(
            Criterion criterion, String output, Map<String, Object> context) {

        double score = 50.0; // Start neutral

        // Length-based heuristic (longer = more complete, up to a point)
        int length = output.length();
        if (length < 50) {
            score -= 20; // Too short
        } else if (length > 200) {
            score += 10; // Good length
        }
        if (length > 500) {
            score += 5; // Detailed
        }

        // Structure heuristics
        if (output.contains("\n")) {
            score += 5; // Has structure
        }
        if (output.contains("1.") || output.contains("- ")) {
            score += 5; // Has lists
        }

        // Check for evaluation logic keywords in output
        String logic = criterion.getEvaluationLogic();
        if (logic != null && !logic.isBlank()) {
            // Extract keywords from evaluation logic
            String[] keywords = logic.toLowerCase().split("\\s+");
            int matches = 0;
            String lowerOutput = output.toLowerCase();
            for (String keyword : keywords) {
                if (keyword.length() > 3 && lowerOutput.contains(keyword)) {
                    matches++;
                }
            }
            // Add points for keyword matches
            score += Math.min(20, matches * 5);
        }

        // Clamp to 0-100
        return Math.max(0, Math.min(100, score));
    }
}
