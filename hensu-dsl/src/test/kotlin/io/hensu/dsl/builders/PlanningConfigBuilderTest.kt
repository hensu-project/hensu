package io.hensu.dsl.builders

import io.hensu.core.plan.PlanningMode
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlanningConfigBuilderTest {

    @Nested
    inner class DefaultValues {

        @Test
        fun `should have disabled mode by default`() {
            val builder = PlanningConfigBuilder()

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.DISABLED)
        }

        @Test
        fun `should have default constraints`() {
            val builder = PlanningConfigBuilder()

            val config = builder.build()

            assertThat(config.constraints().maxSteps()).isEqualTo(10)
            assertThat(config.constraints().maxReplans()).isEqualTo(3)
            assertThat(config.constraints().maxDuration()).isEqualTo(Duration.ofMinutes(5))
            assertThat(config.constraints().allowReplan()).isTrue()
        }

        @Test
        fun `should not review before execute by default`() {
            val builder = PlanningConfigBuilder()

            val config = builder.build()

            assertThat(config.reviewBeforeExecute()).isFalse()
        }
    }

    @Nested
    inner class ModeConfiguration {

        @Test
        fun `should set dynamic mode`() {
            val builder = PlanningConfigBuilder()
            builder.mode = PlanningMode.DYNAMIC

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.DYNAMIC)
            assertThat(config.isDynamic).isTrue()
        }

        @Test
        fun `should set static mode`() {
            val builder = PlanningConfigBuilder()
            builder.mode = PlanningMode.STATIC

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.STATIC)
            assertThat(config.isStatic).isTrue()
        }
    }

    @Nested
    inner class ConstraintConfiguration {

        @Test
        fun `should set maxSteps`() {
            val builder = PlanningConfigBuilder()
            builder.maxSteps = 25

            val config = builder.build()

            assertThat(config.constraints().maxSteps()).isEqualTo(25)
        }

        @Test
        fun `should set maxReplans`() {
            val builder = PlanningConfigBuilder()
            builder.maxReplans = 5

            val config = builder.build()

            assertThat(config.constraints().maxReplans()).isEqualTo(5)
        }

        @Test
        fun `should set maxDuration`() {
            val builder = PlanningConfigBuilder()
            builder.maxDuration = Duration.ofMinutes(15)

            val config = builder.build()

            assertThat(config.constraints().maxDuration()).isEqualTo(Duration.ofMinutes(15))
        }

        @Test
        fun `should set allowReplan`() {
            val builder = PlanningConfigBuilder()
            builder.allowReplan = false

            val config = builder.build()

            assertThat(config.constraints().allowReplan()).isFalse()
        }

        @Test
        fun `should set maxTokenBudget`() {
            val builder = PlanningConfigBuilder()
            builder.maxTokenBudget = 50000

            val config = builder.build()

            assertThat(config.constraints().maxTokenBudget()).isEqualTo(50000)
        }
    }

    @Nested
    inner class ReviewConfiguration {

        @Test
        fun `should set reviewBeforeExecute`() {
            val builder = PlanningConfigBuilder()
            builder.reviewBeforeExecute = true

            val config = builder.build()

            assertThat(config.reviewBeforeExecute()).isTrue()
        }

        @Test
        fun `withReview should enable review`() {
            val builder = PlanningConfigBuilder()
            builder.withReview()

            val config = builder.build()

            assertThat(config.reviewBeforeExecute()).isTrue()
        }
    }

    @Nested
    inner class PresetConfigurations {

        @Test
        fun `static should configure for static plans`() {
            val builder = PlanningConfigBuilder()
            builder.static()

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.STATIC)
            assertThat(config.constraints().allowReplan()).isFalse()
            assertThat(config.constraints().maxReplans()).isEqualTo(0)
            assertThat(config.constraints().maxDuration()).isEqualTo(Duration.ofMinutes(30))
            assertThat(config.constraints().maxTokenBudget()).isEqualTo(0)
        }

        @Test
        fun `dynamic should configure for dynamic plans`() {
            val builder = PlanningConfigBuilder()
            builder.dynamic()

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.DYNAMIC)
            assertThat(config.constraints().allowReplan()).isTrue()
            assertThat(config.constraints().maxReplans()).isEqualTo(3)
            assertThat(config.constraints().maxDuration()).isEqualTo(Duration.ofMinutes(5))
            assertThat(config.constraints().maxTokenBudget()).isEqualTo(10000)
        }

        @Test
        fun `noReplan should disable replanning`() {
            val builder = PlanningConfigBuilder()
            builder.mode = PlanningMode.DYNAMIC
            builder.noReplan()

            val config = builder.build()

            assertThat(config.constraints().allowReplan()).isFalse()
            assertThat(config.constraints().maxReplans()).isEqualTo(0)
        }
    }

    @Nested
    inner class CombinedConfiguration {

        @Test
        fun `should support full configuration`() {
            val builder = PlanningConfigBuilder()
            builder.mode = PlanningMode.DYNAMIC
            builder.maxSteps = 20
            builder.maxReplans = 5
            builder.maxDuration = Duration.ofMinutes(10)
            builder.maxTokenBudget = 25000
            builder.allowReplan = true
            builder.reviewBeforeExecute = true

            val config = builder.build()

            assertThat(config.mode()).isEqualTo(PlanningMode.DYNAMIC)
            assertThat(config.constraints().maxSteps()).isEqualTo(20)
            assertThat(config.constraints().maxReplans()).isEqualTo(5)
            assertThat(config.constraints().maxDuration()).isEqualTo(Duration.ofMinutes(10))
            assertThat(config.constraints().maxTokenBudget()).isEqualTo(25000)
            assertThat(config.constraints().allowReplan()).isTrue()
            assertThat(config.reviewBeforeExecute()).isTrue()
        }
    }
}
