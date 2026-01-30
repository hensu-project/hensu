package io.hensu.dsl.parsers

import io.hensu.dsl.WorkingDirectory
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
                    description = "Test workflow"
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

                    graph {
                        start at "review"

                        // Node with simple success transition
                        node("review") {
                            agent = "reviewer"
                            prompt = "Review this code: {code}"

                            // Simple success transition
                            onSuccess goto "approve"

                            // Failure with retry
                            onFailure retry 2 otherwise "reject"

                            // Optional review
                            review(ReviewMode.OPTIONAL)
                        }

                        // Node with score-based transitions
                        node("quality-check") {
                            agent = "reviewer"
                            prompt = "Check quality"

                            // Score-based routing
                            onScore {
                                whenScore greaterThanOrEqual 90.0 goto "excellent"
                                whenScore `in` 70.0..89.0 goto "good"
                                whenScore `in` 50.0..69.0 goto "needs-work"
                                whenScore lessThan 50.0 goto "reject"
                            }

                            // Required review with backtracking
                            review {
                                mode = ReviewMode.REQUIRED
                                allowBacktrack = true
                                allowEdit = true
                            }
                        }

                        // Node with detailed review
                        node("final-check") {
                            agent = "reviewer"
                            prompt = "Final check"

                            onSuccess goto "approve"

                            review {
                                mode = ReviewMode.REQUIRED
                                allowBacktrack = true
                                allowEdit = false
                            }
                        }

                        // End nodes
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
        assertThat(graph.nodes.containsKey("review")).isTrue
    }
}
