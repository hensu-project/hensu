package io.hensu.dsl.builders

import io.hensu.core.review.ReviewMode
import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.SuccessTransition
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.asKotlinRange
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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
}
