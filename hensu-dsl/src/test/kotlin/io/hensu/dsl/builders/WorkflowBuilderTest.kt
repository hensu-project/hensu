package io.hensu.dsl.builders

import io.hensu.core.workflow.state.VarType
import io.hensu.dsl.WorkingDirectory
import io.hensu.dsl.workflow
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkflowBuilderTest {

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
    fun `should propagate state schema to workflow`() {
        val workflow =
            workflow("WithState", workingDir) {
                agents {
                    agent("agent1") {
                        role = "Agent"
                        model = "test"
                    }
                }

                state {
                    input("topic", VarType.STRING)
                    variable("summary", VarType.STRING)
                }

                graph {
                    start at "step1"
                    node("step1") {
                        agent = "agent1"
                        writes("summary")
                        onSuccess goto "end"
                    }
                    end("end")
                }
            }

        assertThat(workflow.stateSchema).isNotNull
        val vars = (workflow.stateSchema ?: return).variables
        assertThat(vars).hasSize(2)

        val topic = vars.first { it.name() == "topic" }
        assertThat(topic.isInput).isTrue()
        assertThat(topic.type()).isEqualTo(VarType.STRING)

        val summary = vars.first { it.name() == "summary" }
        assertThat(summary.isInput).isFalse()
    }

    @Test
    fun `should throw exception when graph not defined`() {
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
        assertThatThrownBy {
                workflow("NoStart", workingDir) {
                    graph {
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
        assertThatThrownBy {
                workflow("InvalidAgent", workingDir) {
                    agents {
                        agent("bad-agent") {
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
    fun `should sanitize workflow ID`() {
        val workflow =
            workflow("My Test Workflow!!!", workingDir) {
                graph {
                    start at "step1"
                    node("step1") { onSuccess goto "end" }
                    end("end")
                }
            }

        assertThat(workflow.id).isEqualTo("my_test_workflow")
    }
}
