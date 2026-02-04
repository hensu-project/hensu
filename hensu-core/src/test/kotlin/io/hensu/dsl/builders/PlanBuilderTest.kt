package io.hensu.dsl.builders

import io.hensu.core.plan.Plan.PlanSource
import io.hensu.core.plan.PlannedStep.StepStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlanBuilderTest {

    @Nested
    inner class StepCreation {

        @Test
        fun `should create plan with single step`() {
            val builder = PlanBuilder()
            builder.step("get_order") { description = "Fetch order" }

            val plan = builder.build("test-node")

            assertThat(plan.steps()).hasSize(1)
            assertThat(plan.steps()[0].toolName()).isEqualTo("get_order")
            assertThat(plan.steps()[0].description()).isEqualTo("Fetch order")
            assertThat(plan.steps()[0].index()).isEqualTo(0)
            assertThat(plan.steps()[0].status()).isEqualTo(StepStatus.PENDING)
        }

        @Test
        fun `should create plan with multiple steps`() {
            val builder = PlanBuilder()
            builder.step("step1") { description = "First" }
            builder.step("step2") { description = "Second" }
            builder.step("step3") { description = "Third" }

            val plan = builder.build()

            assertThat(plan.steps()).hasSize(3)
            assertThat(plan.steps()[0].index()).isEqualTo(0)
            assertThat(plan.steps()[1].index()).isEqualTo(1)
            assertThat(plan.steps()[2].index()).isEqualTo(2)
        }

        @Test
        fun `should create simple step with description`() {
            val builder = PlanBuilder()
            builder.step("validate", "Validate input data")

            val plan = builder.build()

            assertThat(plan.steps()[0].toolName()).isEqualTo("validate")
            assertThat(plan.steps()[0].description()).isEqualTo("Validate input data")
            assertThat(plan.steps()[0].arguments()).isEmpty()
        }
    }

    @Nested
    inner class StepArguments {

        @Test
        fun `should set arguments using args`() {
            val builder = PlanBuilder()
            builder.step("get_order") {
                args("id" to "{orderId}", "format" to "json")
                description = "Fetch order"
            }

            val plan = builder.build()

            assertThat(plan.steps()[0].arguments())
                .containsEntry("id", "{orderId}")
                .containsEntry("format", "json")
        }

        @Test
        fun `should set single argument using arg`() {
            val builder = PlanBuilder()
            builder.step("search") {
                arg("query", "test query")
                description = "Search"
            }

            val plan = builder.build()

            assertThat(plan.steps()[0].arguments()).containsEntry("query", "test query")
        }

        @Test
        fun `should support mixed argument types`() {
            val builder = PlanBuilder()
            builder.step("process") {
                args("count" to 5, "enabled" to true, "rate" to 0.75)
                description = "Process"
            }

            val plan = builder.build()
            val args = plan.steps()[0].arguments()

            assertThat(args["count"]).isEqualTo(5)
            assertThat(args["enabled"]).isEqualTo(true)
            assertThat(args["rate"]).isEqualTo(0.75)
        }
    }

    @Nested
    inner class PlanMetadata {

        @Test
        fun `should set nodeId when building with nodeId`() {
            val builder = PlanBuilder()
            builder.step("test") { description = "Test" }

            val plan = builder.build("my-node")

            assertThat(plan.nodeId()).isEqualTo("my-node")
        }

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
        fun `should generate unique plan ID`() {
            val builder1 = PlanBuilder()
            builder1.step("test") { description = "Test" }

            val builder2 = PlanBuilder()
            builder2.step("test") { description = "Test" }

            val plan1 = builder1.build()
            val plan2 = builder2.build()

            assertThat(plan1.id()).isNotEqualTo(plan2.id())
        }

        @Test
        fun `should use static plan constraints`() {
            val builder = PlanBuilder()
            builder.step("test") { description = "Test" }

            val plan = builder.build()

            assertThat(plan.constraints().allowReplan()).isFalse()
        }
    }

    @Nested
    inner class EmptyPlan {

        @Test
        fun `should allow empty plan`() {
            val builder = PlanBuilder()

            val plan = builder.build()

            assertThat(plan.steps()).isEmpty()
            assertThat(plan.hasSteps()).isFalse()
        }
    }
}
