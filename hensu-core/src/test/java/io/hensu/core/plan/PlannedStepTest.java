package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.PlannedStep.StepStatus;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlannedStepTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenIndexIsNegative() {
            assertThatThrownBy(
                            () ->
                                    new PlannedStep(
                                            -1,
                                            new PlanStepAction.ToolCall("tool", Map.of()),
                                            "desc",
                                            StepStatus.PENDING))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index must be >= 0");
        }

        @Test
        void shouldThrowWhenActionIsNull() {
            assertThatThrownBy(() -> new PlannedStep(0, null, "desc", StepStatus.PENDING))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("action");
        }

        @Test
        void shouldThrowWhenToolNameIsNull() {
            assertThatThrownBy(
                            () ->
                                    new PlannedStep(
                                            0,
                                            new PlanStepAction.ToolCall(null, Map.of()),
                                            "desc",
                                            StepStatus.PENDING))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        void shouldThrowWhenToolNameIsBlank() {
            assertThatThrownBy(
                            () ->
                                    new PlannedStep(
                                            0,
                                            new PlanStepAction.ToolCall("  ", Map.of()),
                                            "desc",
                                            StepStatus.PENDING))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void shouldMakeDefensiveCopyOfArguments() {
            Map<String, Object> args = new HashMap<>();
            args.put("key", "value");
            PlannedStep step = PlannedStep.pending(0, "tool", args, "desc");

            args.put("newKey", "newValue");

            assertThat(step.arguments()).doesNotContainKey("newKey");
        }

        @Test
        void shouldRejectMutationOfReturnedArgumentMap() {
            PlannedStep step = PlannedStep.pending(0, "tool", Map.of("k", "v"), "desc");

            assertThatThrownBy(() -> step.arguments().put("extra", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class FinishedStatus {

        @Test
        void shouldDetectFinishedSteps() {
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.PENDING).isFinished())
                    .isFalse();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.EXECUTING).isFinished())
                    .isFalse();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.COMPLETED).isFinished())
                    .isTrue();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.FAILED).isFinished())
                    .isTrue();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.SKIPPED).isFinished())
                    .isTrue();
        }

        @Test
        void shouldDetectSuccessfulSteps() {
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.COMPLETED).isSuccess())
                    .isTrue();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.FAILED).isSuccess())
                    .isFalse();
            assertThat(PlannedStep.simple(0, "t", "").withStatus(StepStatus.PENDING).isSuccess())
                    .isFalse();
        }
    }
}
