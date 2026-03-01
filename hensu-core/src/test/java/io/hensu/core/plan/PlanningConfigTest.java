package io.hensu.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanningConfigTest {

    @Nested
    class FactoryMethods {

        @Test
        void disabledShouldCreateDisabledConfig() {
            PlanningConfig config = PlanningConfig.disabled();

            assertThat(config.mode()).isEqualTo(PlanningMode.DISABLED);
            assertThat(config.isEnabled()).isFalse();
            assertThat(config.review()).isFalse();
        }

        @Test
        void forStaticShouldCreateStaticConfig() {
            PlanningConfig config = PlanningConfig.forStatic();

            assertThat(config.mode()).isEqualTo(PlanningMode.STATIC);
            assertThat(config.isStatic()).isTrue();
            assertThat(config.isDynamic()).isFalse();
            assertThat(config.constraints().allowReplan()).isFalse();
        }

        @Test
        void forStaticWithReviewShouldEnableReview() {
            PlanningConfig config = PlanningConfig.forStaticWithReview();

            assertThat(config.mode()).isEqualTo(PlanningMode.STATIC);
            assertThat(config.review()).isTrue();
        }

        @Test
        void forDynamicShouldCreateDynamicConfig() {
            PlanningConfig config = PlanningConfig.forDynamic();

            assertThat(config.mode()).isEqualTo(PlanningMode.DYNAMIC);
            assertThat(config.isDynamic()).isTrue();
            assertThat(config.isStatic()).isFalse();
            assertThat(config.constraints().allowReplan()).isTrue();
        }

        @Test
        void forDynamicWithReviewShouldEnableReview() {
            PlanningConfig config = PlanningConfig.forDynamicWithReview();

            assertThat(config.mode()).isEqualTo(PlanningMode.DYNAMIC);
            assertThat(config.review()).isTrue();
        }
    }

    @Nested
    class QueryMethods {

        @Test
        void isEnabledShouldReturnTrueForNonDisabled() {
            assertThat(PlanningConfig.forStatic().isEnabled()).isTrue();
            assertThat(PlanningConfig.forDynamic().isEnabled()).isTrue();
            assertThat(PlanningConfig.disabled().isEnabled()).isFalse();
        }

        @Test
        void isStaticShouldReturnTrueOnlyForStatic() {
            assertThat(PlanningConfig.forStatic().isStatic()).isTrue();
            assertThat(PlanningConfig.forDynamic().isStatic()).isFalse();
            assertThat(PlanningConfig.disabled().isStatic()).isFalse();
        }

        @Test
        void isDynamicShouldReturnTrueOnlyForDynamic() {
            assertThat(PlanningConfig.forDynamic().isDynamic()).isTrue();
            assertThat(PlanningConfig.forStatic().isDynamic()).isFalse();
            assertThat(PlanningConfig.disabled().isDynamic()).isFalse();
        }
    }

    @Nested
    class WithMethods {

        @Test
        void withConstraintsShouldUpdateConstraints() {
            PlanningConfig original = PlanningConfig.forDynamic();
            PlanConstraints newConstraints = PlanConstraints.noReplan();

            PlanningConfig updated = original.withConstraints(newConstraints);

            assertThat(updated.constraints()).isEqualTo(newConstraints);
            assertThat(updated.mode()).isEqualTo(original.mode());
            assertThat(updated.review()).isEqualTo(original.review());
        }

        @Test
        void withReviewShouldEnableReview() {
            PlanningConfig original = PlanningConfig.forDynamic();

            PlanningConfig updated = original.withReview();

            assertThat(updated.review()).isTrue();
            assertThat(updated.mode()).isEqualTo(original.mode());
        }

        @Test
        void withoutReviewShouldDisableReview() {
            PlanningConfig original = PlanningConfig.forDynamicWithReview();

            PlanningConfig updated = original.withoutReview();

            assertThat(updated.review()).isFalse();
        }

        @Test
        void withMaxDurationShouldUpdateDuration() {
            PlanningConfig original = PlanningConfig.forDynamic();
            Duration newDuration = Duration.ofMinutes(15);

            PlanningConfig updated = original.withMaxDuration(newDuration);

            assertThat(updated.constraints().maxDuration()).isEqualTo(newDuration);
        }

        @Test
        void withMaxStepsShouldUpdateSteps() {
            PlanningConfig original = PlanningConfig.forDynamic();

            PlanningConfig updated = original.withMaxSteps(25);

            assertThat(updated.constraints().maxSteps()).isEqualTo(25);
        }
    }

    @Nested
    class DefaultValues {

        @Test
        void shouldDefaultToDisabledModeWhenNull() {
            PlanningConfig config = new PlanningConfig(null, null, false);

            assertThat(config.mode()).isEqualTo(PlanningMode.DISABLED);
        }

        @Test
        void shouldDefaultConstraintsWhenNull() {
            PlanningConfig config = new PlanningConfig(PlanningMode.DYNAMIC, null, false);

            assertThat(config.constraints()).isNotNull();
            assertThat(config.constraints().maxSteps()).isEqualTo(10);
        }
    }
}
