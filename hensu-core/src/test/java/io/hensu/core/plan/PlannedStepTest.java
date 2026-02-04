package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.plan.PlannedStep.StepStatus;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlannedStepTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenIndexIsNegative() {
            assertThatThrownBy(
                            () -> new PlannedStep(-1, "tool", Map.of(), "desc", StepStatus.PENDING))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index must be >= 0");
        }

        @Test
        void shouldThrowWhenToolNameIsNull() {
            assertThatThrownBy(() -> new PlannedStep(0, null, Map.of(), "desc", StepStatus.PENDING))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolName");
        }

        @Test
        void shouldThrowWhenToolNameIsBlank() {
            assertThatThrownBy(() -> new PlannedStep(0, "  ", Map.of(), "desc", StepStatus.PENDING))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void shouldDefaultArgumentsToEmptyMap() {
            PlannedStep step = new PlannedStep(0, "tool", null, "desc", StepStatus.PENDING);

            assertThat(step.arguments()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultDescriptionToEmpty() {
            PlannedStep step = new PlannedStep(0, "tool", Map.of(), null, StepStatus.PENDING);

            assertThat(step.description()).isNotNull().isEmpty();
        }

        @Test
        void shouldDefaultStatusToPending() {
            PlannedStep step = new PlannedStep(0, "tool", Map.of(), "desc", null);

            assertThat(step.status()).isEqualTo(StepStatus.PENDING);
        }

        @Test
        void shouldMakeDefensiveCopyOfArguments() {
            Map<String, Object> args = new java.util.HashMap<>();
            args.put("key", "value");

            PlannedStep step = new PlannedStep(0, "tool", args, "desc", StepStatus.PENDING);

            args.put("newKey", "newValue");

            assertThat(step.arguments()).doesNotContainKey("newKey");
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreatePendingStep() {
            PlannedStep step = PlannedStep.pending(0, "search", Map.of("q", "test"), "Search");

            assertThat(step.index()).isZero();
            assertThat(step.toolName()).isEqualTo("search");
            assertThat(step.arguments()).containsEntry("q", "test");
            assertThat(step.description()).isEqualTo("Search");
            assertThat(step.status()).isEqualTo(StepStatus.PENDING);
        }

        @Test
        void shouldCreateSimpleStep() {
            PlannedStep step = PlannedStep.simple(1, "ping", "Ping service");

            assertThat(step.index()).isEqualTo(1);
            assertThat(step.toolName()).isEqualTo("ping");
            assertThat(step.arguments()).isEmpty();
            assertThat(step.description()).isEqualTo("Ping service");
        }
    }

    @Nested
    class StatusTransitions {

        @Test
        void shouldUpdateStatus() {
            PlannedStep pending = PlannedStep.simple(0, "tool", "Test");

            PlannedStep executing = pending.withStatus(StepStatus.EXECUTING);
            PlannedStep completed = executing.withStatus(StepStatus.COMPLETED);

            assertThat(pending.status()).isEqualTo(StepStatus.PENDING);
            assertThat(executing.status()).isEqualTo(StepStatus.EXECUTING);
            assertThat(completed.status()).isEqualTo(StepStatus.COMPLETED);
        }

        @Test
        void shouldPreserveOtherFieldsWhenUpdatingStatus() {
            PlannedStep original = PlannedStep.pending(5, "tool", Map.of("a", 1), "Desc");

            PlannedStep updated = original.withStatus(StepStatus.COMPLETED);

            assertThat(updated.index()).isEqualTo(5);
            assertThat(updated.toolName()).isEqualTo("tool");
            assertThat(updated.arguments()).containsEntry("a", 1);
            assertThat(updated.description()).isEqualTo("Desc");
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
