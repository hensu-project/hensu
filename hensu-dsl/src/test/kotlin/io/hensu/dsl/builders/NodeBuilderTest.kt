package io.hensu.dsl.builders

import io.hensu.core.workflow.transition.ApprovalTransition
import io.hensu.dsl.WorkingDirectory
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
    fun `should preserve writes list for output extraction`() {
        val builder = StandardNodeBuilder("extractor", workingDir)
        builder.apply {
            agent = "agent1"
            writes("code", "confidence")
            onSuccess goto "next"
        }

        val node = builder.build()

        assertThat(node.writes).containsExactly("code", "confidence")
    }

    @Test
    fun `should build approval transitions with correct polarity`() {
        val builder = StandardNodeBuilder("reviewer", workingDir)
        builder.apply {
            agent = "agent1"
            onApproval goto "finalize"
            onRejection goto "improve"
        }

        val node = builder.build()

        val approvalTransitions = node.transitionRules.filterIsInstance<ApprovalTransition>()
        assertThat(approvalTransitions).hasSize(2)

        val approveRoute = approvalTransitions.first { it.expected() }
        assertThat(approveRoute.targetNode()).isEqualTo("finalize")

        val rejectRoute = approvalTransitions.first { !it.expected() }
        assertThat(rejectRoute.targetNode()).isEqualTo("improve")
    }

    @Test
    fun `should reject writes that collide with engine variables`() {
        val builder = StandardNodeBuilder("node1", workingDir)

        assertThatThrownBy {
                builder.apply {
                    agent = "agent1"
                    writes("score")
                }
            }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved engine variable")
    }

    // A workflow with no state {} block never reaches WorkflowValidator's engine-key loop, so this
    // require is the only thing standing between an author and a node that can erase its own
    // refusal record. The message is asserted whole because pointing at onCondition is the part
    // that turns a refusal into a workable instruction.
    @Test
    fun `should reject writes that collide with the capability gap keys`() {
        val builder = StandardNodeBuilder("node1", workingDir)

        assertThatThrownBy {
                builder.apply {
                    agent = "agent1"
                    writes("_capability_gaps")
                }
            }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Node 'node1': writes field '_capability_gaps' is written by the engine \u2013 " +
                    "it records tool refusals there. Route on it with " +
                    "onCondition(\"_capability_gap_count\"); never declare it."
            )
    }
}
