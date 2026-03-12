package io.hensu.dsl.parsers

import io.hensu.core.workflow.node.StandardNode
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.builders.Models
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinScriptParserTest {

    @TempDir lateinit var tempDir: Path

    private var kotlinParser: KotlinScriptParser = KotlinScriptParser()

    @Test
    fun `test Kotlin DSL parser`() {
        // Create working directory structure
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))

        val kotlinContent =
            """
            fun example() {
                val workflow = workflow("ExampleWorkflow") {
                    description = "Example workflow"
                    version = "1.0.0"

                    agents {
                        agent("reviewer") {
                            role = "Code Reviewer"
                            model = Models.CLAUDE_SONNET_4_5
                            temperature = 0.3
                        }

                        agent("fixer") {
                            role = "Code Fixer"
                            model = Models.GPT_4
                            temperature = 0.7
                        }
                    }

                    state {
                        input("code",    VarType.STRING)
                        variable("review",  VarType.STRING, "code review findings and suggestions")
                        variable("verdict", VarType.STRING, "final verdict after quality check")
                    }

                    graph {
                        start at "review"

                        node("review") {
                            agent = "reviewer"
                            prompt = "Review this code: {code}"
                            writes("review")
                            onSuccess goto "quality-check"
                            onFailure retry 2 otherwise "reject"
                            review(ReviewMode.OPTIONAL)
                        }

                        node("quality-check") {
                            agent = "reviewer"
                            prompt = "Check quality of the review: {review}"
                            writes("verdict")
                            onScore {
                                whenScore greaterThanOrEqual 90.0 goto "excellent"
                                whenScore `in` 70.0..89.0 goto "good"
                                whenScore `in` 50.0..69.0 goto "needs-work"
                                whenScore lessThan 50.0 goto "reject"
                            }
                            review {
                                mode = ReviewMode.REQUIRED
                                allowBacktrack = true
                                allowEdit = true
                            }
                        }

                        node("final-check") {
                            agent = "reviewer"
                            prompt = "Final check: {verdict}"
                            writes("verdict")
                            onSuccess goto "approve"
                            review {
                                mode = ReviewMode.REQUIRED
                                allowBacktrack = true
                                allowEdit = false
                            }
                        }

                        end("approve", ExitStatus.SUCCESS)
                        end("excellent", ExitStatus.SUCCESS)
                        end("good", ExitStatus.SUCCESS)
                        end("needs-work")
                        end("reject", ExitStatus.FAILURE)
                    }
                }
            }
        """
                .trimIndent()

        val workflowFile = tempDir.resolve("workflows/example.kt")
        workflowFile.writeText(kotlinContent)

        val workingDir = WorkingDirectory.of(tempDir)
        val graph = kotlinParser.parse(workingDir, "example")

        assertThat(graph.metadata.name).isEqualTo("ExampleWorkflow")
        assertThat(graph.metadata.description).isEqualTo("Example workflow")
        assertThat(graph.startNode).isEqualTo("review")

        assertThat(graph.agents).hasSize(2)
        assertThat(graph.agents).containsKeys("reviewer", "fixer")
        assertThat(graph.agents["reviewer"]?.model).isEqualTo(Models.CLAUDE_SONNET_4_5)

        assertThat(graph.nodes)
            .containsKeys(
                "review",
                "quality-check",
                "final-check",
                "approve",
                "excellent",
                "good",
                "needs-work",
                "reject",
            )

        val reviewNode = graph.nodes["review"] as StandardNode
        assertThat(reviewNode.writes).containsExactly("review")

        val schema = graph.stateSchema
        assertThat(schema).isNotNull()
        assertThat(schema.variables.map { it.name() }).containsExactly("code", "review", "verdict")
    }
}
