package io.hensu.dsl.builders

import io.hensu.core.review.ReviewMode
import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.workflow.node.StandardNode
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.SuccessTransition
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.extensions.asKotlinRange
import io.hensu.dsl.workflow
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TransitionBuilderTest {

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
    fun `should build simple success transition`() {
        // Given/When
        val workflow =
            workflow("SimpleTransition", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Test"
                        onSuccess goto "step2"
                    }

                    node("step2") {
                        agent = "agent1"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.transitionRules).hasSize(1)

        val successTransition = step1.transitionRules[0] as SuccessTransition
        assertThat(successTransition.targetNode).isEqualTo("step2")
    }

    @Test
    fun `should build failure transition with retry`() {
        // Given/When
        val workflow =
            workflow("FailureTransition", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Test"

                        onSuccess goto "end"
                        onFailure retry 3 otherwise "fallback"
                    }

                    node("fallback") {
                        agent = "agent1"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.transitionRules).hasSize(2)

        val failureTransition = step1.transitionRules.filterIsInstance<FailureTransition>().first()

        assertThat(failureTransition.retryCount).isEqualTo(3)
        assertThat(failureTransition.targetNode).isEqualTo("fallback")
    }

    @Test
    fun `should build score-based transitions`() {
        // Given/When
        val workflow =
            workflow("ScoreTransitions", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Test"
                        rubric = "quality"

                        onScore {
                            whenScore greaterThanOrEqual 90.0 goto "excellent"
                            whenScore `in` 70.0..89.0 goto "good"
                            whenScore lessThan 70.0 goto "poor"
                        }
                    }

                    end("excellent")
                    end("good")
                    end("poor")
                }
            }

        // Then
        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.transitionRules).hasSize(1)

        val scoreTransition = step1.transitionRules[0] as ScoreTransition
        assertThat(scoreTransition.conditions).hasSize(3)

        val gteCondition = scoreTransition.conditions[0]
        assertThat(gteCondition.operator).isEqualTo(ComparisonOperator.GTE)
        assertThat(gteCondition.value).isEqualTo(90.0)
        assertThat(gteCondition.targetNode).isEqualTo("excellent")

        val rangeCondition = scoreTransition.conditions[1]
        assertThat(rangeCondition.operator).isEqualTo(ComparisonOperator.RANGE)
        assertThat(rangeCondition.range.asKotlinRange).isEqualTo(70.0..89.0)

        val ltCondition = scoreTransition.conditions[2]
        assertThat(ltCondition.operator).isEqualTo(ComparisonOperator.LT)
        assertThat(ltCondition.value).isEqualTo(70.0)
    }

    @Test
    fun `should build review configuration`() {
        // Given/When
        val workflow =
            workflow("ReviewConfig", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Test"

                        review {
                            mode = ReviewMode.REQUIRED
                            allowBacktrack = true
                            allowEdit = true
                        }

                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.reviewConfig).isNotNull
        assertThat((step1.reviewConfig ?: return).mode).isEqualTo(ReviewMode.REQUIRED)
        assertThat((step1.reviewConfig ?: return).allowBacktrack).isTrue
        assertThat((step1.reviewConfig ?: return).allowEdit).isTrue
    }

    @Test
    fun `should support multiple transition types in same node`() {
        // Given/When
        val workflow =
            workflow("MultipleTransitions", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Test"
                        rubric = "quality"

                        // All three types of transitions
                        onSuccess goto "default-success"
                        onFailure retry 2 otherwise "failure-handler"
                        onScore { whenScore greaterThan 95.0 goto "exceptional" }
                    }

                    end("default-success")
                    end("failure-handler")
                    end("exceptional")
                }
            }

        // Then
        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.transitionRules).hasSize(3)

        assertThat(step1.transitionRules[0]).isInstanceOf(SuccessTransition::class.java)
        assertThat(step1.transitionRules[1]).isInstanceOf(FailureTransition::class.java)
        assertThat(step1.transitionRules[2]).isInstanceOf(ScoreTransition::class.java)
    }
}
