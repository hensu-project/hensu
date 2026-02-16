package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.AutoBacktrack;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExecutionStep;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.RubricParser;
import io.hensu.core.rubric.evaluator.DefaultRubricEvaluator;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.ScoreTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.nio.file.Path;
import java.util.*;
import java.util.Optional;
import java.util.logging.Logger;

/// Evaluates rubric quality criteria and triggers auto-backtracking on failure.
///
/// When a node has a `rubricId`, this processor:
/// 1. Registers the rubric in the engine if not already present
/// 2. Evaluates the node output against the rubric
/// 3. Stores the evaluation in state for {@link ScoreTransition} rules
/// 4. If evaluation fails and no user-defined score transition matches,
///    determines an auto-backtrack target based on score severity
///
/// ### Backtracking Thresholds
/// - Critical failure (score < 30): Return to the earliest logical step
/// - Moderate failure (score < 60): Return to previous phase
/// - Minor failure (score < 80): Retry current node (up to max retries)
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Returns empty or terminal result
/// - **Side effects**: Mutates `state.rubricEvaluation`, may mutate context map
///   and history on auto-backtrack
///
/// @implNote Receives {@link RubricEngine} via constructor injection. Stateless
/// beyond the injected engine reference.
///
/// @see RubricEngine for evaluation logic
/// @see RubricEvaluation for evaluation result format
/// @see AutoBacktrack for backtrack instruction
public final class RubricPostProcessor implements PostNodeExecutionProcessor {

    private static final Logger logger = Logger.getLogger(RubricPostProcessor.class.getName());

    private static final double CRITICAL_FAILURE_THRESHOLD = 30.0;
    private static final double MODERATE_FAILURE_THRESHOLD = 60.0;
    private static final double MINOR_FAILURE_THRESHOLD = 80.0;
    private static final int DEFAULT_MAX_BACKTRACK_RETRIES = 3;

    private final RubricEngine rubricEngine;

    /// Creates a rubric processor with the given engine.
    ///
    /// @param rubricEngine engine for rubric evaluation, not null
    public RubricPostProcessor(RubricEngine rubricEngine) {
        this.rubricEngine = rubricEngine;
    }

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        var node = context.currentNode();
        if (node.getRubricId() == null) {
            return Optional.empty();
        }

        registerRubricIfAbsent(node.getRubricId(), context.workflow());

        RubricEvaluation evaluation;
        try {
            evaluation =
                    rubricEngine.evaluate(
                            node.getRubricId(), context.result(), context.state().getContext());
        } catch (RubricNotFoundException e) {
            logger.severe(
                    "Rubric evaluation failed for node " + node.getId() + ": " + e.getMessage());
            return Optional.of(
                    new ExecutionResult.Failure(
                            context.state(), new IllegalStateException(e.getMessage(), e)));
        }

        context.state().setRubricEvaluation(evaluation);

        if (!evaluation.isPassed()) {
            boolean hasMatchingScoreTransition =
                    hasMatchingScoreTransition(node, context.state(), context.result());

            if (!hasMatchingScoreTransition) {
                AutoBacktrack autoBacktrack =
                        determineAutoBacktrack(
                                evaluation, node, context.workflow(), context.state());

                if (autoBacktrack != null) {
                    logger.info(
                            "Auto-backtracking from "
                                    + node.getId()
                                    + " to "
                                    + autoBacktrack.getTargetNode()
                                    + " due to rubric failure");

                    HensuState state = context.state();
                    String targetNode = autoBacktrack.getTargetNode();
                    state.setCurrentNode(targetNode);
                    state.getContext().putAll(autoBacktrack.getContextUpdates());
                    state.getHistory()
                            .addAutoBacktrack(
                                    node.getId(),
                                    targetNode,
                                    "Rubric score: " + evaluation.getScore(),
                                    evaluation);

                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    private AutoBacktrack determineAutoBacktrack(
            RubricEvaluation evaluation, Node currentNode, Workflow workflow, HensuState state) {

        List<String> selfRecommendations =
                (List<String>) state.getContext().get(DefaultRubricEvaluator.RECOMMENDATIONS_KEY);

        if (evaluation.getScore() < CRITICAL_FAILURE_THRESHOLD) {
            String startNode = findEarliestLogicalStep(state, workflow);
            Map<String, Object> updates = new HashMap<>();
            updates.put("backtrack_reason", "Critical rubric failure: " + evaluation.getScore());
            updates.put("failed_criteria", evaluation.getFailedCriteria());
            addRecommendationsToContext(updates, selfRecommendations, evaluation);
            return new AutoBacktrack(startNode, updates);
        }

        if (evaluation.getScore() < MODERATE_FAILURE_THRESHOLD) {
            String previousPhase = findPreviousPhase(currentNode.getId(), state, workflow);
            if (previousPhase != null) {
                Map<String, Object> updates = new HashMap<>();
                updates.put(
                        "backtrack_reason", "Moderate rubric failure: " + evaluation.getScore());
                updates.put("improvement_suggestions", evaluation.getSuggestions());
                addRecommendationsToContext(updates, selfRecommendations, evaluation);
                return new AutoBacktrack(previousPhase, updates);
            }
        }

        if (evaluation.getScore() < MINOR_FAILURE_THRESHOLD) {
            Integer retryAttempt = (Integer) state.getContext().get("retry_attempt");
            int currentAttempt = retryAttempt != null ? retryAttempt : 0;

            if (currentAttempt >= DEFAULT_MAX_BACKTRACK_RETRIES) {
                logger.warning(
                        "Max backtrack retries ("
                                + DEFAULT_MAX_BACKTRACK_RETRIES
                                + ") reached for node "
                                + currentNode.getId()
                                + " with score "
                                + evaluation.getScore());
                return null;
            }

            Map<String, Object> updates = new HashMap<>();
            updates.put("retry_attempt", currentAttempt + 1);
            updates.put("improvement_hints", evaluation.getSuggestions());
            addRecommendationsToContext(updates, selfRecommendations, evaluation);
            return new AutoBacktrack(currentNode.getId(), updates);
        }

        return null;
    }

    private void addRecommendationsToContext(
            Map<String, Object> updates,
            List<String> selfRecommendations,
            RubricEvaluation evaluation) {

        StringBuilder recommendations = new StringBuilder();

        if (selfRecommendations != null && !selfRecommendations.isEmpty()) {
            recommendations.append("Self-evaluation recommendations:\n");
            for (String rec : selfRecommendations) {
                recommendations.append("- ").append(rec).append("\n");
            }
        }

        List<String> rubricSuggestions = evaluation.getSuggestions();
        if (rubricSuggestions != null && !rubricSuggestions.isEmpty()) {
            if (!recommendations.isEmpty()) {
                recommendations.append("\n");
            }
            recommendations.append("Rubric improvement areas:\n");
            for (String suggestion : rubricSuggestions) {
                recommendations.append("- ").append(suggestion).append("\n");
            }
        }

        if (!recommendations.isEmpty()) {
            updates.put("recommendations", recommendations.toString().trim());
            logger.info("Injecting recommendations into backtrack context:\n" + recommendations);
        }
    }

    private String findEarliestLogicalStep(HensuState state, Workflow workflow) {
        Optional<ExecutionStep> firstMajorStep =
                state.getHistory().getSteps().stream()
                        .filter(
                                step -> {
                                    Node node = workflow.getNodes().get(step.getNodeId());
                                    return node != null
                                            && node.getRubricId() != null
                                            && !node.getRubricId().isEmpty();
                                })
                        .findFirst();

        return firstMajorStep.map(ExecutionStep::getNodeId).orElse(workflow.getStartNode());
    }

    private String findPreviousPhase(String currentNodeId, HensuState state, Workflow workflow) {
        Node currentNode = workflow.getNodes().get(currentNodeId);
        String currentRubric = currentNode != null ? currentNode.getRubricId() : null;

        List<ExecutionStep> steps = state.getHistory().getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            ExecutionStep step = steps.get(i);
            Node stepNode = workflow.getNodes().get(step.getNodeId());
            if (stepNode != null
                    && stepNode.getRubricId() != null
                    && !stepNode.getRubricId().isEmpty()
                    && !Objects.equals(stepNode.getRubricId(), currentRubric)) {
                return step.getNodeId();
            }
        }

        return null;
    }

    private boolean hasMatchingScoreTransition(
            Node node, HensuState state, io.hensu.core.execution.executor.NodeResult result) {
        for (TransitionRule rule : node.getTransitionRules()) {
            if (rule instanceof ScoreTransition st && st.evaluate(state, result) != null) {
                return true;
            }
        }
        return false;
    }

    private void registerRubricIfAbsent(String rubricId, Workflow workflow) {
        if (!rubricEngine.exists(rubricId)) {
            Rubric rubric = loadRubric(rubricId, workflow.getRubrics());
            rubricEngine.registerRubric(rubric);
        }
    }

    private Rubric loadRubric(String rubricId, Map<String, String> rubrics) {
        String rubricPath = rubrics.get(rubricId);
        if (rubricPath == null) {
            throw new IllegalStateException("Rubric not found: " + rubricId);
        }
        return RubricParser.parse(Path.of(rubricPath));
    }
}
