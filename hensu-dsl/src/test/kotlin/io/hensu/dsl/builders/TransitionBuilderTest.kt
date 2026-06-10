package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.workflow.node.StandardNode
import io.hensu.core.workflow.transition.ScoreTransition
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
    fun `should build score-based transitions with correct operators and ranges`() {
        Files.writeString(tempDir.resolve("rubrics/quality.md"), "# Quality Rubric")

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
                        rubric = "quality.md"

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

        val step1 = workflow.nodes["step1"] as StandardNode
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
}
