package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RubricFailTransitionTest {

    private HensuState state;

    @BeforeEach
    void setUp() {
        state =
                new HensuState(
                        new HashMap<>(), "test-workflow", "current-node", new ExecutionHistory());
    }

    @Test
    void shouldEvaluateUsingProvidedFunction() {
        // Given
        RubricFailTransition transition =
                new RubricFailTransition(
                        evaluation -> evaluation.isPassed() ? "continue" : "revise");

        RubricEvaluation failedEval =
                RubricEvaluation.builder().rubricId("quality").score(50.0).passed(false).build();
        state.setRubricEvaluation(failedEval);

        NodeResult result = NodeResult.success("output", Map.of());

        // When
        String target = transition.evaluate(state, result);

        // Then
        assertThat(target).isEqualTo("revise");
    }

    @Test
    void shouldReturnContinueWhenPassed() {
        // Given
        RubricFailTransition transition =
                new RubricFailTransition(
                        evaluation -> evaluation.isPassed() ? "continue" : "revise");

        RubricEvaluation passedEval =
                RubricEvaluation.builder().rubricId("quality").score(90.0).passed(true).build();
        state.setRubricEvaluation(passedEval);

        NodeResult result = NodeResult.success("output", Map.of());

        // When
        String target = transition.evaluate(state, result);

        // Then
        assertThat(target).isEqualTo("continue");
    }

    @Test
    void shouldAccessScoreInFunction() {
        // Given
        RubricFailTransition transition =
                new RubricFailTransition(
                        evaluation -> {
                            if (evaluation.getScore() >= 80) return "excellent";
                            if (evaluation.getScore() >= 60) return "good";
                            return "needs-improvement";
                        });

        RubricEvaluation eval =
                RubricEvaluation.builder().rubricId("quality").score(75.0).passed(true).build();
        state.setRubricEvaluation(eval);

        NodeResult result = NodeResult.success("output", Map.of());

        // When
        String target = transition.evaluate(state, result);

        // Then
        assertThat(target).isEqualTo("good");
    }

    @Test
    void shouldAccessRubricIdInFunction() {
        // Given
        RubricFailTransition transition =
                new RubricFailTransition(evaluation -> "handled-" + evaluation.getRubricId());

        RubricEvaluation eval =
                RubricEvaluation.builder()
                        .rubricId("content-quality")
                        .score(50.0)
                        .passed(false)
                        .build();
        state.setRubricEvaluation(eval);

        NodeResult result = NodeResult.success("output", Map.of());

        // When
        String target = transition.evaluate(state, result);

        // Then
        assertThat(target).isEqualTo("handled-content-quality");
    }

    @Test
    void shouldExposeFunction() {
        // Given
        var func = (java.util.function.Function<RubricEvaluation, String>) _ -> "target";
        RubricFailTransition transition = new RubricFailTransition(func);

        // Then
        assertThat(transition.function()).isSameAs(func);
    }

    @Test
    void shouldImplementTransitionRule() {
        // Given
        RubricFailTransition transition = new RubricFailTransition(_ -> "target");

        // Then
        assertThat(transition).isInstanceOf(TransitionRule.class);
    }
}
