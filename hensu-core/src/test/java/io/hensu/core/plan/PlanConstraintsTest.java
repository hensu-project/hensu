package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanConstraintsTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenMaxStepsIsNegative() {
            assertThatThrownBy(() -> new PlanConstraints(-1, 3, Duration.ofMinutes(5), 10000, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxSteps");
        }

        @Test
        void shouldThrowWhenMaxReplansIsNegative() {
            assertThatThrownBy(
                            () -> new PlanConstraints(10, -1, Duration.ofMinutes(5), 10000, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxReplans");
        }

        @Test
        void shouldThrowWhenMaxTokenBudgetIsNegative() {
            assertThatThrownBy(() -> new PlanConstraints(10, 3, Duration.ofMinutes(5), -1, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTokenBudget");
        }

        @Test
        void shouldDefaultMaxDuration() {
            PlanConstraints constraints = new PlanConstraints(10, 3, null, 10000, true);

            assertThat(constraints.maxDuration()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateDefaults() {
            PlanConstraints defaults = PlanConstraints.defaults();

            assertThat(defaults.maxSteps()).isEqualTo(10);
            assertThat(defaults.maxReplans()).isEqualTo(3);
            assertThat(defaults.maxDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(defaults.maxTokenBudget()).isEqualTo(10000);
            assertThat(defaults.allowReplan()).isTrue();
        }

        @Test
        void shouldCreateNoReplan() {
            PlanConstraints noReplan = PlanConstraints.noReplan();

            assertThat(noReplan.maxReplans()).isZero();
            assertThat(noReplan.allowReplan()).isFalse();
        }

        @Test
        void shouldCreateForStaticPlan() {
            PlanConstraints staticPlan = PlanConstraints.forStaticPlan();

            assertThat(staticPlan.maxSteps()).isEqualTo(100);
            assertThat(staticPlan.maxReplans()).isZero();
            assertThat(staticPlan.maxDuration()).isEqualTo(Duration.ofMinutes(30));
            assertThat(staticPlan.maxTokenBudget()).isZero();
            assertThat(staticPlan.allowReplan()).isFalse();
        }
    }

    @Nested
    class WithMethods {

        @Test
        void shouldUpdateMaxSteps() {
            PlanConstraints original = PlanConstraints.defaults();

            PlanConstraints updated = original.withMaxSteps(50);

            assertThat(original.maxSteps()).isEqualTo(10);
            assertThat(updated.maxSteps()).isEqualTo(50);
            assertThat(updated.maxReplans()).isEqualTo(original.maxReplans());
        }

        @Test
        void shouldUpdateMaxDuration() {
            PlanConstraints original = PlanConstraints.defaults();

            PlanConstraints updated = original.withMaxDuration(Duration.ofHours(1));

            assertThat(original.maxDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(updated.maxDuration()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        void shouldDisableReplan() {
            PlanConstraints original = PlanConstraints.defaults();

            PlanConstraints updated = original.withoutReplan();

            assertThat(original.allowReplan()).isTrue();
            assertThat(updated.allowReplan()).isFalse();
            assertThat(updated.maxReplans()).isZero();
        }
    }
}
