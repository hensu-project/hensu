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
import java.util.Optional;

/// Quality evaluation engine for rubric-based output assessment.
///
/// Evaluates workflow node outputs against parsed {@link Rubric} objects to determine
/// quality scores and pass/fail status. Supports weighted criteria evaluation
/// and provides detailed per-criterion feedback.
///
/// ### Repository placeholder
///
/// The {@link RubricRepository} field and its accessor methods ({@link #registerRubric},
/// {@link #exists}, {@link #getRubric}) are not used by the current execution pipeline.
/// Rubrics are parsed at build time and stored directly on the node — the engine
/// receives them as typed {@link Rubric} arguments via {@link #evaluate}. The repository
/// is retained as a placeholder for a future DB-backed rubric store where rubrics are
/// managed independently of workflow definitions.
///
/// ### Contracts
/// - **Postcondition**: Returns complete evaluation with all criteria scores
/// - **Invariant**: Rubric scores are normalized to 0-100 scale
///
/// @implNote Thread-safe if the underlying repository and evaluator are thread-safe.
/// The engine itself maintains no mutable state beyond the repository delegate.
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

    /// Registers a rubric in the repository for later retrieval.
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

    /// Returns the rubric registered under the given ID.
    ///
    /// @param rubricId the rubric identifier to look up, not null
    /// @return the registered rubric, or empty if not found
    public Optional<Rubric> getRubric(String rubricId) {
        return repository.findById(rubricId);
    }

    /// Evaluates a node result against the given rubric.
    ///
    /// Calculates weighted scores for each criterion and produces an overall
    /// score normalized to 0-100. The evaluation passes if the score meets
    /// the rubric's pass threshold.
    ///
    /// @param rubric the parsed rubric to evaluate against, not null
    /// @param result node execution result to evaluate, not null
    /// @param context execution context for evaluation, not null
    /// @return evaluation result with score and criterion details, never null
    public RubricEvaluation evaluate(
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

        double finalScore = maxScore > 0 ? totalScore / maxScore : 0.0;
        boolean passed = finalScore >= rubric.getPassThreshold();

        return RubricEvaluation.builder()
                .rubricId(rubric.getId())
                .score(finalScore)
                .passed(passed)
                .criterionEvaluations(criterionEvaluations)
                .build();
    }
}
