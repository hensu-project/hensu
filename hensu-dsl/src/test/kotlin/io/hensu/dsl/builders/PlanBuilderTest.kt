package io.hensu.dsl.builders

import io.hensu.core.plan.Plan.PlanSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlanBuilderTest {

    @Nested
    inner class DefaultValues {
        @Test
        fun `should have empty nodeId when building without nodeId`() {
            val builder = PlanBuilder()
            builder.step("test") { description = "Test" }

            val plan = builder.build()

            assertThat(plan.nodeId()).isEmpty()
        }

        @Test
        fun `should set source to STATIC`() {
            val builder = PlanBuilder()
            builder.step("test") { description = "Test" }

            val plan = builder.build()

            assertThat(plan.source()).isEqualTo(PlanSource.STATIC)
            assertThat(plan.isStatic).isTrue()
        }

        @Test
        fun `should allow empty plan`() {
            val builder = PlanBuilder()

            val plan = builder.build()

            assertThat(plan.steps()).isEmpty()
            assertThat(plan.hasSteps()).isFalse()
        }
    }
}
