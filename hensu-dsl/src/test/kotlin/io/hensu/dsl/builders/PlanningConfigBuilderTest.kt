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

            assertThat(config.review()).isFalse()
        }
    }
}
