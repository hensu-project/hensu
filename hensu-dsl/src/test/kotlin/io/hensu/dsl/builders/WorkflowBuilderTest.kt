package io.hensu.dsl.builders

import io.hensu.core.execution.result.ExitStatus
import io.hensu.core.review.ReviewMode
import io.hensu.core.workflow.node.StandardNode
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.workflow
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Tests for Kotlin DSL workflow builder. */
class WorkflowBuilderTest {

    @TempDir lateinit var tempDir: Path

    private lateinit var workingDir: WorkingDirectory

    @BeforeEach
    fun setUp() {
        // Create working directory structure
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))
        workingDir = WorkingDirectory.of(tempDir)
    }

    @Test
    fun `should build simple workflow using DSL`() {
        // When
        val workflow =
            workflow("SimpleTest", workingDir) {
                description = "A simple test workflow"
                version = "1.0.0"

                agents {
                    agent("test-agent") {
                        role = "Tester"
                        model = "test-model"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "test-agent"
                        prompt = "Test prompt"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        assertThat(workflow.id).isEqualTo("simpletest")
        assertThat(workflow.version).isEqualTo("1.0.0")
        assertThat(workflow.metadata.description).isEqualTo("A simple test workflow")
        assertThat(workflow.agents).hasSize(1)
        assertThat(workflow.nodes).hasSize(2)
        assertThat(workflow.startNode).isEqualTo("step1")
    }

    @Test
    fun `should build workflow with multiple agents`() {
        // When
        val workflow =
            workflow("MultiAgent", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Agent 1"
                        model = Models.CLAUDE_SONNET_4_5
                        temperature = 0.3
                    }

                    agent("agent2") {
                        role = "Agent 2"
                        model = Models.GPT_4
                        temperature = 0.7
                        maxTokens = 1000
                        tools = listOf("web_search", "calculator")
                    }
                }

                graph {
                    start at "step1"
                    node("step1") {
                        agent = "agent1"
                        onSuccess goto "end"
                    }
                    end("end")
                }
            }

        // Then
        assertThat(workflow.agents).hasSize(2)

        val agent1 = workflow.agents["agent1"]!!
        assertThat(agent1.role).isEqualTo("Agent 1")
        assertThat(agent1.temperature).isEqualTo(0.3)

        val agent2 = workflow.agents["agent2"]!!
        assertThat(agent2.role).isEqualTo("Agent 2")
        assertThat(agent2.maxTokens).isEqualTo(1000)
        assertThat(agent2.tools).containsExactly("web_search", "calculator")
    }

    @Test
    fun `should build workflow with rubrics`() {
        // Create rubric files
        Files.writeString(tempDir.resolve("rubrics/quality.md"), "# Quality Rubric")
        Files.writeString(tempDir.resolve("rubrics/security.md"), "# Security Rubric")

        // When
        val workflow =
            workflow("WithRubrics", workingDir) {
                rubrics {
                    rubric("quality", "quality.md")
                    rubric("security", "security.md")
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "test"
                        rubric = "quality"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        assertThat(workflow.rubrics).hasSize(2)
        assertThat(workflow.rubrics["quality"]).endsWith("rubrics/quality.md")
        assertThat(workflow.rubrics["security"]).endsWith("rubrics/security.md")
    }

    @Test
    fun `should build workflow with score-based transitions`() {
        // When
        val workflow =
            workflow("ScoreBased", workingDir) {
                agents {
                    agent("reviewer") {
                        role = "Reviewer"
                        model = "test"
                    }
                }

                graph {
                    start at "review"

                    node("review") {
                        agent = "reviewer"
                        prompt = "Review this"

                        onScore {
                            whenScore greaterThanOrEqual 80.0 goto "approve"
                            whenScore `in` 60.0..79.0 goto "revise"
                            whenScore lessThan 60.0 goto "reject"
                        }
                    }

                    end("approve", ExitStatus.SUCCESS)
                    end("revise")
                    end("reject", ExitStatus.FAILURE)
                }
            }

        // Then
        val reviewNode = workflow.nodes["review"] as StandardNode
        assertThat(reviewNode.transitionRules).hasSize(1)

        val scoreTransition = reviewNode.transitionRules[0] as ScoreTransition
        assertThat(scoreTransition.conditions).hasSize(3)
    }

    @Test
    fun `should build workflow with retry logic`() {
        // When
        val workflow =
            workflow("WithRetry", workingDir) {
                agents {
                    agent("worker") {
                        role = "Worker"
                        model = "test"
                    }
                }

                graph {
                    start at "work"

                    node("work") {
                        agent = "worker"
                        prompt = "Do work"

                        onSuccess goto "end"
                        onFailure retry 3 otherwise "fallback"
                    }

                    node("fallback") {
                        agent = "worker"
                        prompt = "Fallback logic"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        val workNode = workflow.nodes["work"] as StandardNode
        val failureTransition =
            workNode.transitionRules.filterIsInstance<FailureTransition>().first()

        assertThat(failureTransition.retryCount).isEqualTo(3)
        assertThat(failureTransition.thenTargetNode).isEqualTo("fallback")
    }

    @Test
    fun `should build workflow with human review`() {
        // When
        val workflow =
            workflow("WithReview", workingDir) {
                agents {
                    agent("worker") {
                        role = "Worker"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "worker"
                        prompt = "Do work"

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
        assertThat(step1.reviewConfig!!.mode).isEqualTo(ReviewMode.REQUIRED)
        assertThat(step1.reviewConfig!!.allowBacktrack).isTrue
        assertThat(step1.reviewConfig!!.allowEdit).isTrue
    }

    @Test
    fun `should sanitize workflow ID`() {
        // When
        val workflow =
            workflow("My Test Workflow!!!", workingDir) {
                graph {
                    start at "step1"
                    node("step1") { onSuccess goto "end" }
                    end("end")
                }
            }

        // Then
        assertThat(workflow.id).isEqualTo("my_test_workflow")
    }

    @Test
    fun `should throw exception when graph not defined`() {
        // When/Then
        assertThatThrownBy {
                workflow("NoGraph", workingDir) {
                    // No graph defined
                }
            }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Graph not defined")
    }

    @Test
    fun `should throw exception when start node not defined`() {
        // When/Then
        assertThatThrownBy {
                workflow("NoStart", workingDir) {
                    graph {
                        // No start node defined
                        node("step1") { onSuccess goto "end" }
                        end("end")
                    }
                }
            }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Start node not defined")
    }

    @Test
    fun `should validate agent configuration`() {
        // When/Then
        assertThatThrownBy {
                workflow("InvalidAgent", workingDir) {
                    agents {
                        agent("bad-agent") {
                            // Missing required fields
                            role = ""
                            model = ""
                        }
                    }

                    graph {
                        start at "step1"
                        node("step1") {
                            agent = "bad-agent"
                            onSuccess goto "end"
                        }
                        end("end")
                    }
                }
            }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `should support method chaining`() {
        // When
        val workflow =
            workflow("Chaining", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Agent"
                        model = "test"
                    }
                }

                graph {
                    start at "step1"

                    node("step1") {
                        agent = "agent1"
                        prompt = "Step 1"
                        rubric = "quality"

                        review(ReviewMode.OPTIONAL)

                        onSuccess goto "step2"
                        onFailure retry 2 otherwise "end"
                    }

                    node("step2") {
                        agent = "agent1"
                        prompt = "Step 2"
                        onSuccess goto "end"
                    }

                    end("end")
                }
            }

        // Then
        assertThat(workflow.nodes).hasSize(3)

        val step1 = workflow.nodes["step1"] as StandardNode
        assertThat(step1.agentId).isEqualTo("agent1")
        assertThat(step1.prompt).isEqualTo("Step 1")
        assertThat(step1.rubricId).isEqualTo("quality")
        assertThat(step1.reviewConfig?.mode).isEqualTo(ReviewMode.OPTIONAL)
    }
}
