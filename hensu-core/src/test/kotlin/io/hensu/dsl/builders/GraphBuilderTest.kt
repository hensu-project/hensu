package io.hensu.dsl.builders

import io.hensu.core.execution.result.ExitStatus
import io.hensu.core.workflow.node.ActionNode
import io.hensu.core.workflow.node.EndNode
import io.hensu.core.workflow.node.StandardNode
import io.hensu.dsl.WorkingDirectory
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GraphBuilderTest {

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
    fun `should build simple graph`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "step1"

            node("step1") {
                agent = "agent1"
                prompt = "Test"
                onSuccess goto "end"
            }

            end("end")
        }

        val result = builder.build()

        // Then
        assertThat(result.startNode).isEqualTo("step1")
        assertThat(result.nodes).hasSize(2)
        assertThat(result.nodes).containsKeys("step1", "end")
    }

    @Test
    fun `should build graph with multiple nodes`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "step1"

            node("step1") {
                agent = "agent1"
                onSuccess goto "step2"
            }

            node("step2") {
                agent = "agent2"
                onSuccess goto "step3"
            }

            node("step3") {
                agent = "agent3"
                onSuccess goto "end"
            }

            end("end")
        }

        val result = builder.build()

        // Then
        assertThat(result.nodes).hasSize(4)
        assertThat(result.nodes.keys).containsExactlyInAnyOrder("step1", "step2", "step3", "end")
    }

    @Test
    fun `should build graph with exit statuses`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "step1"

            node("step1") {
                onSuccess goto "success"
                onFailure retry 0 otherwise "failure"
            }

            end("success", ExitStatus.SUCCESS)
            end("failure", ExitStatus.FAILURE)
        }

        val result = builder.build()

        // Then
        val successNode = result.nodes["success"] as EndNode
        val failureNode = result.nodes["failure"] as EndNode

        assertThat(successNode.exitStatus).isEqualTo(ExitStatus.SUCCESS)
        assertThat(failureNode.exitStatus).isEqualTo(ExitStatus.FAILURE)
    }

    @Test
    fun `should support branching logic`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "decision"

            node("decision") {
                agent = "decider"
                prompt = "Make decision"

                onScore {
                    whenScore greaterThan 80.0 goto "path_a"
                    whenScore lessThanOrEqual 80.0 goto "path_b"
                }
            }

            node("path_a") {
                agent = "handler_a"
                onSuccess goto "end"
            }

            node("path_b") {
                agent = "handler_b"
                onSuccess goto "end"
            }

            end("end")
        }

        val result = builder.build()

        // Then
        assertThat(result.nodes).hasSize(4)

        val decisionNode = result.nodes["decision"] as StandardNode
        assertThat(decisionNode.transitionRules).hasSize(1)
    }

    @Test
    fun `should validate start node exists in graph`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            node("step1") { onSuccess goto "end" }

            end("end")
        }

        // Then
        assertThatThrownBy { builder.build() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `should build graph with action nodes`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "develop"

            node("develop") {
                agent = "coder"
                prompt = "Write code"
                onSuccess goto "commit"
            }

            action("commit") {
                execute("git-commit")
                onSuccess goto "end"
            }

            end("end")
        }

        val result = builder.build()

        // Then
        assertThat(result.nodes).hasSize(3)
        assertThat(result.nodes).containsKeys("develop", "commit", "end")

        val actionNode = result.nodes["commit"] as ActionNode
        assertThat(actionNode.actions).hasSize(1)
        assertThat(actionNode.transitionRules).hasSize(1)
    }

    @Test
    fun `should build action node with multiple actions`() {
        // Given
        val builder = GraphBuilder(workingDir)

        // When
        builder.apply {
            start at "deploy"

            action("deploy") {
                execute("deploy-prod")
                notify("Deployment started", "slack")
                http("https://webhook.example.com", "deploy-hook")
                onSuccess goto "end"
                onFailure retry 2 otherwise "rollback"
            }

            end("end")
            end("rollback", ExitStatus.FAILURE)
        }

        val result = builder.build()

        // Then
        val actionNode = result.nodes["deploy"] as ActionNode
        assertThat(actionNode.actions).hasSize(3)
        assertThat(actionNode.transitionRules).hasSize(2)
    }
}
