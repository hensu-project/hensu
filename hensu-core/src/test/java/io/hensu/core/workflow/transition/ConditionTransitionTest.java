package io.hensu.core.workflow.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.CapabilityGaps;
import io.hensu.core.workflow.transition.Condition.Compare;
import io.hensu.core.workflow.transition.Condition.Equals;
import io.hensu.core.workflow.transition.Condition.NotEquals;
import io.hensu.core.workflow.transition.Condition.Op;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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

    /// An engine-owned gap key is written only when something is refused, so its absence is what a
    /// healthy run looks like rather than a workflow routing on the wrong name.
    @Nested
    class WhenRoutingOnACapabilityGapKey {

        @Test
        void shouldStaySilentWhenNothingWasRefused() {
            var transition =
                    new ConditionTransition(
                            CapabilityGaps.COUNT_KEY, new Compare(Op.GTE, 1), TARGET);
            var state = stateWithout();

            assertThat(transition.evaluate(state, RESULT)).isNull();
            // The recommended shape is a gap arm ahead of an ordinary one. Warning here would fire
            // on every clean run of it, which is how an operator learns to ignore warnings.
            assertThat(transition.mismatchDiagnostic(state)).isNull();
        }

        @Test
        void shouldStillRouteOnceSomethingWasRefused() {
            var transition =
                    new ConditionTransition(
                            CapabilityGaps.COUNT_KEY, new Compare(Op.GTE, 1), TARGET);
            var state = stateWithGapCount(1);

            assertThat(transition.evaluate(state, RESULT)).isEqualTo(TARGET);
            assertThat(transition.mismatchDiagnostic(state)).isNull();
        }

        @Test
        void shouldStillReportAGapKeyHoldingSomethingUncoercible() {
            var transition =
                    new ConditionTransition(
                            CapabilityGaps.COUNT_KEY, new Compare(Op.GTE, 1), TARGET);
            var state = stateWithGapCount("several");

            // Silence is only for absence. A gap key holding a non-number means something wrote it
            // that should not have, and that is a defect worth a warning.
            assertThat(transition.evaluate(state, RESULT)).isNull();
            assertThat(transition.mismatchDiagnostic(state)).contains(CapabilityGaps.COUNT_KEY);
        }

        @Test
        void shouldStillReportAnOrdinaryVariableThatIsAbsent() {
            var transition = new ConditionTransition("status", new Compare(Op.GTE, 1), TARGET);
            var state = stateWithout();

            // An ordinary absent variable is still a typo or a missing writes(), and still loud.
            assertThat(transition.mismatchDiagnostic(state)).contains("status");
        }
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private HensuState stateWithout() {
        return new HensuState(
                new HashMap<>(), "test-workflow", "current-node", new ExecutionHistory());
    }

    private HensuState stateWithGapCount(Object value) {
        var context = new HashMap<String, Object>();
        context.put(CapabilityGaps.COUNT_KEY, value);
        return new HensuState(context, "test-workflow", "current-node", new ExecutionHistory());
    }

    private HensuState stateWith(Object value) {
        var context = new HashMap<String, Object>();
        if (value != null) {
            context.put("status", value);
        }
        return new HensuState(context, "test-workflow", "current-node", new ExecutionHistory());
    }

    @Test
    @DisplayName("rejects an engine variable — onApproval and onScore own those semantics")
    void rejectsEngineVariable() {
        assertThatThrownBy(
                        () ->
                                new ConditionTransition(
                                        "approved", new Condition.Equals("true"), "next"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onApproval");
    }
}
