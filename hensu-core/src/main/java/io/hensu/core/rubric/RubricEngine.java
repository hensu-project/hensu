package io.hensu.core.rubric;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.rubric.evaluator.RubricEvaluator;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.CriterionEvaluation;
import io.hensu.core.rubric.model.Rubric;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Quality evaluation engine for rubric-based output assessment.
///
/// Evaluates workflow node outputs against configurable rubrics to determine
/// quality scores and pass/fail status. Supports weighted criteria evaluation
/// and provides detailed per-criterion feedback.
///
/// ### Contracts
/// - **Precondition**: Rubric must be registered before evaluation
/// - **Postcondition**: Returns complete evaluation with all criteria scores
/// - **Invariant**: Rubric scores are normalized to 0-100 scale
///
/// @implNote Thread-safe if the underlying repository and evaluator are thread-safe.
/// The engine itself maintains no mutable state.
///
/// @see RubricEvaluator for criterion evaluation logic
/// @see Rubric for rubric definition structure
/// @see RubricEvaluation for evaluation result format
public final class RubricEngine {

    private final RubricRepository repository;
    private final RubricEvaluator evaluator;

    /// Creates a rubric engine with the specified repository and evaluator.
    ///
    /// @param repository storage for rubric definitions, not null
    /// @param evaluator criterion evaluation strategy, not null
    public RubricEngine(RubricRepository repository, RubricEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    /// Registers a rubric in the repository for later evaluation.
    ///
    /// @apiNote **Side effects**: Modifies the rubric repository
    ///
    /// @param rubric the rubric definition to register, not null
    public void registerRubric(Rubric rubric) {
        repository.save(rubric);
    }

    /// Checks if a rubric is already registered.
    ///
    /// @param rubricId the rubric identifier to check, not null
    /// @return true if a rubric with this ID exists in the repository
    public boolean exists(String rubricId) {
        return repository.exists(rubricId);
    }

    /// Evaluates a node result against a registered rubric.
    ///
    /// Calculates weighted scores for each criterion and produces an overall
    /// score normalized to 0-100. The evaluation passes if the score meets
    /// the rubric's pass threshold.
    ///
    /// ### Performance
    /// - Time: O(n) where n = number of criteria in rubric
    /// - Space: O(n) for criterion evaluation results
    ///
    /// @param rubricId identifier of the registered rubric, not null
    /// @param result node execution result to evaluate, not null
    /// @param context execution context for evaluation, not null
    /// @return evaluation result with score and criterion details, never null
    /// @throws RubricNotFoundException if rubricId is not registered
    public RubricEvaluation evaluate(
            String rubricId, NodeResult result, Map<String, Object> context)
            throws RubricNotFoundException {

        Rubric rubric =
                repository
                        .findById(rubricId)
                        .orElseThrow(
                                () -> new RubricNotFoundException("Rubric not found: " + rubricId));

        return evaluateRubric(rubric, result, context);
    }

    private RubricEvaluation evaluateRubric(
            Rubric rubric, NodeResult result, Map<String, Object> context) {

        List<CriterionEvaluation> criterionEvaluations = new ArrayList<>();
        double totalScore = 0.0;
        double maxScore = 0.0;

        for (Criterion criterion : rubric.getCriteria()) {
            double score = evaluator.evaluate(criterion, result, context);
            double weightedScore = score * criterion.getWeight();

            CriterionEvaluation evaluation =
                    CriterionEvaluation.builder()
                            .criterionId(criterion.getId())
                            .score(score)
                            .weightedScore(weightedScore)
                            .passed(score >= criterion.getMinScore())
                            .build();

            criterionEvaluations.add(evaluation);
            totalScore += weightedScore;
            maxScore += criterion.getWeight();
        }

        double finalScore = maxScore > 0 ? (totalScore / maxScore) * 100.0 : 0.0;
        boolean passed = finalScore >= rubric.getPassThreshold();

        return RubricEvaluation.builder()
                .rubricId(rubric.getId())
                .score(finalScore)
                .passed(passed)
                .criterionEvaluations(criterionEvaluations)
                .build();
    }
}
