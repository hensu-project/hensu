package io.hensu.core.rubric.evaluator;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.rubric.model.Criterion;
import java.util.Map;

/// Interface for criterion evaluation strategies. Implementations can use different evaluation
/// approaches.
public interface RubricEvaluator {

    /// Evaluate a criterion against result and context.
    ///
    /// @param criterion The criterion to evaluate
    /// @param result The node execution result
    /// @param context The execution context
    /// @return Score (0-100)
    double evaluate(Criterion criterion, NodeResult result, Map<String, Object> context);
}
