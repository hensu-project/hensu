package io.hensu.dsl.builders

import io.hensu.core.execution.parallel.ConsensusStrategy
import io.hensu.dsl.WorkingDirectory
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ParallelNodeBuilderTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var workingDir: WorkingDirectory

    @BeforeEach
    fun setUp() {
        Files.createDirectories(tempDir.resolve("workflows"))
        Files.createDirectories(tempDir.resolve("prompts"))
        Files.createDirectories(tempDir.resolve("rubrics"))
        workingDir = WorkingDirectory.of(tempDir)
    }

    @Nested
    inner class DefaultValues {
        @Test
        fun `should default branch weight to one`() {
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            builder.apply {
                branch("branch-1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onConsensus goto "next"
            }
            val node = builder.build()

            assertThat(node.branches[0].weight()).isEqualTo(1.0)
        }

        @Test
        fun `should work without consensus config`() {
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onSuccess goto "next"
            }
            val node = builder.build()

            assertThat(node.hasConsensus()).isFalse()
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `should throw when no branches defined`() {
            val builder = ParallelNodeBuilder("empty", workingDir)

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one branch")
        }

        @Test
        fun `should throw when branch has no agent`() {
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            builder.apply { branch("branch-1") { prompt = "Review" } }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must have an agent")
        }

        @Test
        fun `should reject yields that collide with engine variables`() {
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            assertThatThrownBy {
                    builder.apply {
                        branch("b1") {
                            agent = "reviewer"
                            prompt = "Review"
                            yields("score")
                        }
                    }
                }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved engine variable")
        }

        @Test
        fun `should throw when judge decides without judge`() {
            val builder = ParallelNodeBuilder("voting", workingDir)

            assertThatThrownBy {
                    builder.apply {
                        branch("b1") {
                            agent = "reviewer"
                            prompt = "Review"
                        }
                        consensus { strategy = ConsensusStrategy.JUDGE_DECIDES }
                    }
                }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("JUDGE_DECIDES strategy requires a judge")
        }
    }
}
