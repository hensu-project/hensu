package io.hensu.dsl.builders

import io.hensu.core.rubric.model.ComparisonOperator
import io.hensu.core.workflow.node.StandardNode
import io.hensu.core.workflow.transition.AlwaysTransition
import io.hensu.core.workflow.transition.BoundedTransition
import io.hensu.core.workflow.transition.Condition
import io.hensu.core.workflow.transition.ConditionTransition
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

    @Test
    fun `should compile onCondition arms in declaration order with else arm`() {
        // First match wins at runtime, so builder must preserve declaration order even
        // when goto, revise, and otherwise arms interleave — reordering silently
        // changes routing.
        val workflow =
            workflow("ConditionTransitions", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Test"
                        model = "test"
                    }
                }

                graph {
                    start at "worker"

                    node("worker") {
                        agent = "agent1"
                        prompt = "Test"
                        writes("status")

                        onCondition("status") {
                            whenValue equalTo "complete" goto "deploy"
                            whenValue equalTo "blocked" revise "worker" retry 3 otherwise "escalate"
                            otherwise goto "review"
                        }
                    }

                    end("deploy")
                    end("escalate")
                    end("review")
                }
            }

        val worker = workflow.nodes["worker"] as StandardNode
        val rules = worker.transitionRules
        assertThat(rules).hasSize(3)

        val exitArm = rules[0] as ConditionTransition
        assertThat(exitArm.variable()).isEqualTo("status")
        assertThat(exitArm.targetNode()).isEqualTo("deploy")
        assertThat(exitArm.condition()).isEqualTo(Condition.Equals("complete"))

        val reviseArm = rules[1] as BoundedTransition
        assertThat(reviseArm.namespace()).isEqualTo("condition")
        assertThat((reviseArm.trigger() as ConditionTransition).condition())
            .isEqualTo(Condition.Equals("blocked"))

        val elseArm = rules[2] as AlwaysTransition
        assertThat(elseArm.targetNode()).isEqualTo("review")
    }

    @Test
    fun `should reject onCondition variable not declared in writes`() {
        // A typo'd or undeclared variable is never requested from the agent, never
        // extracted, and the loop can never exit — must fail at build time, not
        // burn budget at runtime.
        assertThatThrownBy {
                workflow("UndeclaredVariable", workingDir) {
                    agents {
                        agent("agent1") {
                            role = "Test"
                            model = "test"
                        }
                    }

                    graph {
                        start at "worker"

                        node("worker") {
                            agent = "agent1"
                            prompt = "Test"
                            // writes("status") deliberately missing

                            onCondition("status") { whenValue equalTo "complete" goto "deploy" }
                        }

                        end("deploy")
                    }
                }
            }
            .hasMessageContaining("status")
            .hasMessageContaining("writes")
    }

    @Test
    fun `should reject overlapping condition arms`() {
        // greaterThanOrEqual 0.5 and greaterThan 0.7 both match 0.8; first-match-wins would
        // silently mask
        // the second arm — build error, not a runtime surprise.
        assertThatThrownBy {
                workflow("OverlappingArms", workingDir) {
                    agents {
                        agent("agent1") {
                            role = "Test"
                            model = "test"
                        }
                    }

                    graph {
                        start at "worker"

                        node("worker") {
                            agent = "agent1"
                            prompt = "Test"
                            writes("confidence")

                            onCondition("confidence") {
                                whenValue greaterThanOrEqual 0.5 goto "good"
                                whenValue greaterThan 0.7 goto "excellent"
                            }
                        }

                        end("good")
                        end("excellent")
                    }
                }
            }
            .hasMessageContaining("overlap")
    }

    @Test
    fun `should reject arm declared after otherwise`() {
        // The else-arm compiles to an unconditional rule: any arm behind it is dead
        // code the author believes is live — must fail at build time.
        assertThatThrownBy {
                workflow("ArmAfterOtherwise", workingDir) {
                    agents {
                        agent("agent1") {
                            role = "Test"
                            model = "test"
                        }
                    }

                    graph {
                        start at "worker"

                        node("worker") {
                            agent = "agent1"
                            prompt = "Test"
                            writes("status")

                            onCondition("status") {
                                otherwise goto "review"
                                whenValue equalTo "complete" goto "deploy"
                            }
                        }

                        end("review")
                        end("deploy")
                    }
                }
            }
            .hasMessageContaining("otherwise")
    }
}
