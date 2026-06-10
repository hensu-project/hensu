package io.hensu.dsl.builders

import io.hensu.core.plan.PlanningMode
import io.hensu.core.workflow.transition.ApprovalTransition
import io.hensu.dsl.WorkingDirectory
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

    @Nested
    inner class PlanningDefaults {
        @Test
        fun `should default to disabled planning`() {
            val builder = StandardNodeBuilder("no-plan-node", workingDir)

            builder.apply {
                agent = "agent1"
                prompt = "Simple prompt"
                onSuccess goto "next"
            }

            val node = builder.build()

            assertThat(node.hasPlanningEnabled()).isFalse()
            assertThat(node.planningConfig.mode()).isEqualTo(PlanningMode.DISABLED)
            assertThat(node.staticPlan).isNull()
        }
    }
}
