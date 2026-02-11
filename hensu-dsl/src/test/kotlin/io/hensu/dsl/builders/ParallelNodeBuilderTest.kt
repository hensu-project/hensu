package io.hensu.dsl.builders

import io.hensu.core.execution.parallel.ConsensusStrategy
import io.hensu.core.workflow.node.NodeType
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.SuccessTransition
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
    inner class BranchTest {
        @Test
        fun `should build parallel node with single branch`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("branch-1") {
                    agent = "reviewer"
                    prompt = "Review this code"
                }
                onConsensus goto "approved"
            }
            val node = builder.build()

            // Then
            assertThat(node.id).isEqualTo("parallel-1")
            assertThat(node.nodeType).isEqualTo(NodeType.PARALLEL)
            assertThat(node.branches).hasSize(1)
            assertThat(node.branches[0].id()).isEqualTo("branch-1")
            assertThat(node.branches[0].agentId()).isEqualTo("reviewer")
        }

        @Test
        fun `should build parallel node with multiple branches`() {
            // Given
            val builder = ParallelNodeBuilder("voting", workingDir)

            // When
            builder.apply {
                branch("reviewer1") {
                    agent = "senior_dev"
                    prompt = "Review code quality"
                }
                branch("reviewer2") {
                    agent = "security_expert"
                    prompt = "Review security"
                }
                branch("reviewer3") {
                    agent = "architect"
                    prompt = "Review architecture"
                }
                onConsensus goto "merged"
            }
            val node = builder.build()

            // Then
            assertThat(node.branches).hasSize(3)
            assertThat(node.branches.map { it.id() })
                .containsExactly("reviewer1", "reviewer2", "reviewer3")
        }

        @Test
        fun `should set branch rubric`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("branch-1") {
                    agent = "reviewer"
                    prompt = "Review"
                    rubric = "code-quality"
                }
                onConsensus goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.branches[0].rubricId()).isEqualTo("code-quality")
        }

        @Test
        fun `should set branch weight`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("branch-1") {
                    agent = "reviewer"
                    prompt = "Review"
                    weight = 2.5
                }
                onConsensus goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.branches[0].weight()).isEqualTo(2.5)
        }

        @Test
        fun `should default branch weight to one`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("branch-1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onConsensus goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.branches[0].weight()).isEqualTo(1.0)
        }

        @Test
        fun `should throw when no branches defined`() {
            // Given
            val builder = ParallelNodeBuilder("empty", workingDir)

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one branch")
        }

        @Test
        fun `should throw when branch has no agent`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            builder.apply { branch("branch-1") { prompt = "Review" } }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must have an agent")
        }
    }

    @Nested
    inner class ConsensusConfigTest {
        @Test
        fun `should build with majority vote consensus`() {
            // Given
            val builder = ParallelNodeBuilder("voting", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                consensus {
                    strategy = ConsensusStrategy.MAJORITY_VOTE
                    threshold = 0.6
                }
                onConsensus goto "approved"
            }
            val node = builder.build()

            // Then
            assertThat(node.hasConsensus()).isTrue()
            assertThat(node.consensusConfig.strategy).isEqualTo(ConsensusStrategy.MAJORITY_VOTE)
            assertThat(node.consensusConfig.threshold).isEqualTo(0.6)
        }

        @Test
        fun `should build with unanimous consensus`() {
            // Given
            val builder = ParallelNodeBuilder("voting", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                consensus { strategy = ConsensusStrategy.UNANIMOUS }
                onConsensus goto "approved"
            }
            val node = builder.build()

            // Then
            assertThat(node.consensusConfig.strategy).isEqualTo(ConsensusStrategy.UNANIMOUS)
        }

        @Test
        fun `should build with judge decides consensus`() {
            // Given
            val builder = ParallelNodeBuilder("voting", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                consensus {
                    strategy = ConsensusStrategy.JUDGE_DECIDES
                    judge = "senior_reviewer"
                }
                onConsensus goto "approved"
            }
            val node = builder.build()

            // Then
            assertThat(node.consensusConfig.strategy).isEqualTo(ConsensusStrategy.JUDGE_DECIDES)
            assertThat(node.consensusConfig.judgeAgentId).isEqualTo("senior_reviewer")
        }

        @Test
        fun `should throw when judge decides without judge`() {
            // Given
            val builder = ParallelNodeBuilder("voting", workingDir)

            // When/Then - Exception is thrown during consensus configuration, not build
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

        @Test
        fun `should work without consensus config`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.hasConsensus()).isFalse()
        }
    }

    @Nested
    inner class TransitionTest {
        @Test
        fun `should add onConsensus transition`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onConsensus goto "approved"
            }
            val node = builder.build()

            // Then
            val successTransition =
                node.transitionRules.filterIsInstance<SuccessTransition>().first()
            assertThat(successTransition.targetNode).isEqualTo("approved")
        }

        @Test
        fun `should add onNoConsensus transition`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onConsensus goto "approved"
                onNoConsensus goto "needs_review"
            }
            val node = builder.build()

            // Then
            val failureTransition =
                node.transitionRules.filterIsInstance<FailureTransition>().first()
            assertThat(failureTransition.thenTargetNode).isEqualTo("needs_review")
        }

        @Test
        fun `should support onSuccess and onFailure as aliases`() {
            // Given
            val builder = ParallelNodeBuilder("parallel-1", workingDir)

            // When
            builder.apply {
                branch("b1") {
                    agent = "reviewer"
                    prompt = "Review"
                }
                onSuccess goto "next"
                onFailure goto "error"
            }
            val node = builder.build()

            // Then
            assertThat(node.transitionRules).hasSize(2)
        }
    }
}
