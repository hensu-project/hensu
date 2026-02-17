package io.hensu.dsl.builders

import io.hensu.core.plan.Plan.PlanSource
import io.hensu.core.plan.PlanningMode
import io.hensu.core.review.ReviewMode
import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.SuccessTransition
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.asKotlinRange
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NodeBuilderTest {

    @TempDir lateinit var tempDir: Path

    private lateinit var workingDir: WorkingDirectory

    @BeforeEach
    fun setUp() {
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))
        workingDir = WorkingDirectory.of(tempDir)
    }

    @Test
    fun `should build standard node with basic properties`() {
        // Given
        val builder = StandardNodeBuilder("test-node", workingDir)

        // When
        builder.apply {
            agent = "test-agent"
            prompt = "Test prompt with {variable}"
            rubric = "test-rubric"

            onSuccess goto "next"
        }

        val node = builder.build()

        // Then
        assertThat(node.id).isEqualTo("test-node")
        assertThat(node.agentId).isEqualTo("test-agent")
        assertThat(node.prompt).isEqualTo("Test prompt with {variable}")
        assertThat(node.rubricId).isEqualTo("test-rubric")
        assertThat(node.transitionRules).hasSize(1)
    }

    @Test
    fun `should build node with multiple transition rules`() {
        // Given
        val builder = StandardNodeBuilder("multi-transition", workingDir)

        // When
        builder.apply {
            agent = "agent1"
            prompt = "Test"

            onSuccess goto "success"
            onFailure retry 3 otherwise "failure"

            onScore {
                whenScore greaterThanOrEqual 90.0 goto "excellent"
                whenScore `in` 70.0..89.0 goto "good"
                whenScore lessThan 70.0 goto "poor"
            }
        }

        val node = builder.build()

        // Then
        assertThat(node.transitionRules).hasSize(3)

        val successTransition = node.transitionRules.filterIsInstance<SuccessTransition>().first()
        assertThat(successTransition.targetNode).isEqualTo("success")

        val failureTransition = node.transitionRules.filterIsInstance<FailureTransition>().first()
        assertThat(failureTransition.retryCount).isEqualTo(3)
        assertThat(failureTransition.thenTargetNode).isEqualTo("failure")

        val scoreTransition = node.transitionRules.filterIsInstance<ScoreTransition>().first()
        assertThat(scoreTransition.conditions).hasSize(3)
    }

    @Test
    fun `should build node with review configuration`() {
        // Given
        val builder = StandardNodeBuilder("review-node", workingDir)

        // When
        builder.apply {
            agent = "agent1"

            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = true
                allowEdit = false
            }

            onSuccess goto "next"
        }

        val node = builder.build()

        // Then
        assertThat(node.reviewConfig).isNotNull
        assertThat((node.reviewConfig ?: return).mode).isEqualTo(ReviewMode.REQUIRED)
        assertThat((node.reviewConfig ?: return).allowBacktrack).isTrue
        assertThat((node.reviewConfig ?: return).allowEdit).isFalse
    }

    @Test
    fun `should support simple review mode`() {
        // Given
        val builder = StandardNodeBuilder("simple-review", workingDir)

        // When
        builder.apply {
            agent = "agent1"
            review(ReviewMode.OPTIONAL)
            onSuccess goto "next"
        }

        val node = builder.build()

        // Then
        assertThat(node.reviewConfig).isNotNull
        assertThat(node.reviewConfig!!.mode).isEqualTo(ReviewMode.OPTIONAL)
    }

    @Test
    fun `should build score conditions correctly`() {
        // Given
        val builder = StandardNodeBuilder("score-node", workingDir)

        // When
        builder.apply {
            agent = "agent1"

            onScore {
                whenScore greaterThan 90.0 goto "excellent"
                whenScore greaterThanOrEqual 80.0 goto "good"
                whenScore `in` 60.0..79.0 goto "okay"
                whenScore lessThan 60.0 goto "poor"
            }
        }

        val node = builder.build()

        // Then
        val scoreTransition = node.transitionRules.filterIsInstance<ScoreTransition>().first()

        assertThat(scoreTransition.conditions).hasSize(4)

        val gtCondition = scoreTransition.conditions[0]
        assertThat(gtCondition.operator).isEqualTo(ComparisonOperator.GT)
        assertThat(gtCondition.value).isEqualTo(90.0)

        val gteCondition = scoreTransition.conditions[1]
        assertThat(gteCondition.operator).isEqualTo(ComparisonOperator.GTE)

        val rangeCondition = scoreTransition.conditions[2]
        assertThat(rangeCondition.operator).isEqualTo(ComparisonOperator.RANGE)
        assertThat(rangeCondition.range.asKotlinRange).isEqualTo(60.0..79.0)

        val ltCondition = scoreTransition.conditions[3]
        assertThat(ltCondition.operator).isEqualTo(ComparisonOperator.LT)
    }

    @Test
    fun `should allow nodes without agent for special cases`() {
        // Given
        val builder = StandardNodeBuilder("no-agent", workingDir)

        // When
        builder.apply {
            prompt = "Manual step"
            onSuccess goto "next"
        }

        val node = builder.build()

        // Then
        assertThat(node.agentId).isNull()
        assertThat(node.prompt).isEqualTo("Manual step")
    }

    @Nested
    inner class PlanningSupport {

        @Test
        fun `should build node with static plan`() {
            // Given
            val builder = StandardNodeBuilder("plan-node", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                plan {
                    step("get_order") {
                        args("id" to "{orderId}")
                        description = "Fetch order details"
                    }
                    step("validate_order") { description = "Validate order data" }
                }
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            assertThat(node.hasPlanningEnabled()).isTrue()
            assertThat(node.planningConfig.isStatic).isTrue()
            assertThat(node.staticPlan).isNotNull
            assertThat(node.staticPlan!!.steps()).hasSize(2)
            assertThat(node.staticPlan!!.source()).isEqualTo(PlanSource.STATIC)
            assertThat(node.staticPlan!!.nodeId()).isEqualTo("plan-node")
        }

        @Test
        fun `should build node with dynamic planning`() {
            // Given
            val builder = StandardNodeBuilder("dynamic-node", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                planning {
                    mode = PlanningMode.DYNAMIC
                    maxSteps = 15
                    maxReplans = 5
                    allowReplan = true
                    reviewBeforeExecute = true
                }
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            assertThat(node.hasPlanningEnabled()).isTrue()
            assertThat(node.planningConfig.isDynamic).isTrue()
            assertThat(node.planningConfig.constraints().maxSteps()).isEqualTo(15)
            assertThat(node.planningConfig.constraints().maxReplans()).isEqualTo(5)
            assertThat(node.planningConfig.constraints().allowReplan()).isTrue()
            assertThat(node.planningConfig.reviewBeforeExecute()).isTrue()
            assertThat(node.staticPlan).isNull()
        }

        @Test
        fun `should build node with plan failure transition`() {
            // Given
            val builder = StandardNodeBuilder("plan-fail-node", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                plan { step("risky_operation") { description = "Might fail" } }
                onSuccess goto "next"
                onPlanFailure goto "error-handler"
            }

            val node = builder.build()

            // Then
            assertThat(node.planFailureTarget).isEqualTo("error-handler")
        }

        @Test
        fun `should default to disabled planning`() {
            // Given
            val builder = StandardNodeBuilder("no-plan-node", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                prompt = "Simple prompt"
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            assertThat(node.hasPlanningEnabled()).isFalse()
            assertThat(node.planningConfig.mode()).isEqualTo(PlanningMode.DISABLED)
            assertThat(node.staticPlan).isNull()
        }

        @Test
        fun `should configure planning with preset static mode`() {
            // Given
            val builder = StandardNodeBuilder("preset-static", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                planning { static() }
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            assertThat(node.planningConfig.isStatic).isTrue()
            assertThat(node.planningConfig.constraints().allowReplan()).isFalse()
            assertThat(node.planningConfig.constraints().maxDuration())
                .isEqualTo(Duration.ofMinutes(30))
        }

        @Test
        fun `should configure planning with preset dynamic mode`() {
            // Given
            val builder = StandardNodeBuilder("preset-dynamic", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                planning {
                    dynamic()
                    withReview()
                }
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            assertThat(node.planningConfig.isDynamic).isTrue()
            assertThat(node.planningConfig.reviewBeforeExecute()).isTrue()
            assertThat(node.planningConfig.constraints().allowReplan()).isTrue()
        }

        @Test
        fun `should build plan with step arguments`() {
            // Given
            val builder = StandardNodeBuilder("args-plan", workingDir)

            // When
            builder.apply {
                agent = "agent1"
                plan {
                    step("api_call") {
                        args(
                            "url" to "https://api.example.com",
                            "method" to "POST",
                            "body" to "{\"key\": \"{value}\"}",
                        )
                        description = "Call external API"
                    }
                }
                onSuccess goto "next"
            }

            val node = builder.build()

            // Then
            val step = node.staticPlan!!.steps()[0]
            assertThat(step.arguments())
                .containsEntry("url", "https://api.example.com")
                .containsEntry("method", "POST")
                .containsEntry("body", "{\"key\": \"{value}\"}")
        }
    }
}
