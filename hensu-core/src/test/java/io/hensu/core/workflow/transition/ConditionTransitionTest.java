package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.transition.Condition.Compare;
import io.hensu.core.workflow.transition.Condition.Equals;
import io.hensu.core.workflow.transition.Condition.NotEquals;
import io.hensu.core.workflow.transition.Condition.Op;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConditionTransitionTest {

    private static final String TARGET = "next-node";
    private static final NodeResult RESULT = NodeResult.success("output", Map.of());

    /// Coercion contract (ticket #88 defect 1): loose agent typing must still route.
    /// An agent emitting Boolean true against Equals("true"), or a numeric string
    /// against Compare, silently burns the whole loop budget if coercion is
    /// reference-typed.
    static Stream<Arguments> coercionMatrix() {
        return Stream.of(
                // Equals: canonical string form across types
                Arguments.of(new Equals("complete"), "complete", TARGET),
                Arguments.of(new Equals("complete"), "in-progress", null),
                Arguments.of(new Equals("true"), Boolean.TRUE, TARGET),
                Arguments.of(new Equals("false"), Boolean.TRUE, null),
                // number canonicalization: 85.0 renders as "85", not "85.0"
                Arguments.of(new Equals("85"), 85.0, TARGET),
                Arguments.of(new Equals("85"), 85, TARGET),
                Arguments.of(new Equals("85.5"), 85.5, TARGET),
                // NotEquals
                Arguments.of(new NotEquals("blocked"), "complete", TARGET),
                Arguments.of(new NotEquals("blocked"), "blocked", null),
                // Compare: Number and numeric-string leniency
                Arguments.of(new Compare(Op.GTE, 80.0), 85.0, TARGET),
                Arguments.of(new Compare(Op.GTE, 80.0), "85.5", TARGET),
                Arguments.of(new Compare(Op.GTE, 80.0), 79, null),
                Arguments.of(new Compare(Op.LT, 3), 2, TARGET),
                Arguments.of(new Compare(Op.LTE, 3), 3, TARGET),
                Arguments.of(new Compare(Op.GT, 3), 3, null));
    }

    @ParameterizedTest(name = "{0} against {1} → {2}")
    @MethodSource("coercionMatrix")
    void shouldCoerceAgentOutputBeforeMatching(
            Condition condition, Object contextValue, String expectedTarget) {
        var transition = new ConditionTransition("status", condition, TARGET);
        var state = stateWith(contextValue);

        assertThat(transition.evaluate(state, RESULT)).isEqualTo(expectedTarget);
        // a clean match/no-match must never report a mismatch
        assertThat(transition.mismatchDiagnostic(state)).isNull();
    }

    /// Uncoercible values must be a loud mismatch, never a bare no-match — the
    /// silent-false failure mode of the removed LoopNodeExecutor.
    static Stream<Arguments> mismatchMatrix() {
        return Stream.of(
                // absent variable — agent never wrote it (typo, missing writes)
                Arguments.of(new Equals("complete"), null),
                Arguments.of(new NotEquals("blocked"), null),
                Arguments.of(new Compare(Op.GTE, 80.0), null),
                // non-numeric string under a numeric operator
                Arguments.of(new Compare(Op.GTE, 80.0), "excellent"),
                // boolean under a numeric operator
                Arguments.of(new Compare(Op.GTE, 80.0), Boolean.TRUE),
                // structured JSON object — agent emitted a nested payload
                Arguments.of(new Equals("complete"), Map.of("state", "complete")));
    }

    @ParameterizedTest(name = "{0} against {1} → no match + diagnostic")
    @MethodSource("mismatchMatrix")
    void shouldReportTypeMismatchLoudly(Condition condition, Object contextValue) {
        var transition = new ConditionTransition("status", condition, TARGET);
        var state = stateWith(contextValue);

        assertThat(transition.evaluate(state, RESULT)).isNull();
        assertThat(transition.mismatchDiagnostic(state))
                .as("mismatch must produce a diagnostic identifying variable and predicate")
                .contains("status")
                .contains(condition.describe());
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private HensuState stateWith(Object value) {
        var context = new HashMap<String, Object>();
        if (value != null) {
            context.put("status", value);
        }
        return new HensuState(context, "test-workflow", "current-node", new ExecutionHistory());
    }
}
