package io.hensu.dsl.builders

import io.hensu.core.workflow.node.MergeStrategy
import io.hensu.core.workflow.node.NodeType
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.SuccessTransition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ForkJoinBuildersTest {
    @Nested
    inner class ForkNodeBuilderTest {
        @Test
        fun `should build fork node with vararg targets`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")

            // When
            builder.apply {
                targets("task-a", "task-b", "task-c")
                onComplete goto "join"
            }
            val node = builder.build()

            // Then
            assertThat(node.id).isEqualTo("fork-1")
            assertThat(node.nodeType).isEqualTo(NodeType.FORK)
            assertThat(node.targets).containsExactly("task-a", "task-b", "task-c")
        }

        @Test
        fun `should build fork node with list targets`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")
            val targetNodes = listOf("node-1", "node-2")

            // When
            builder.apply {
                targets(targetNodes)
                onComplete goto "join"
            }
            val node = builder.build()

            // Then
            assertThat(node.targets).containsExactly("node-1", "node-2")
        }

        @Test
        fun `should set waitAll property`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")

            // When
            builder.apply {
                targets("task-a")
                waitAll = true
                onComplete goto "join"
            }
            val node = builder.build()

            // Then
            assertThat(node.isWaitForAll).isTrue()
        }

        @Test
        fun `should default waitAll to false`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")

            // When
            builder.apply {
                targets("task-a")
                onComplete goto "join"
            }
            val node = builder.build()

            // Then
            assertThat(node.isWaitForAll).isFalse()
        }

        @Test
        fun `should add onComplete transition`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")

            // When
            builder.apply {
                targets("task-a", "task-b")
                onComplete goto "join-node"
            }
            val node = builder.build()

            // Then
            val successTransition =
                node.transitionRules.filterIsInstance<SuccessTransition>().first()
            assertThat(successTransition.targetNode).isEqualTo("join-node")
        }

        @Test
        fun `should throw when no targets defined`() {
            // Given
            val builder = ForkNodeBuilder("fork-1")

            builder.apply { onComplete goto "join" }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one target")
        }
    }

    @Nested
    inner class JoinNodeBuilderTest {
        @Test
        fun `should build join node with vararg await targets`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1", "fork-2")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.id).isEqualTo("join-1")
            assertThat(node.nodeType).isEqualTo(NodeType.JOIN)
            assertThat(node.awaitTargets).containsExactly("fork-1", "fork-2")
        }

        @Test
        fun `should build join node with list await targets`() {
            // Given
            val builder = JoinNodeBuilder("join-1")
            val forkNodes = listOf("fork-a", "fork-b")

            // When
            builder.apply {
                await(forkNodes)
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.awaitTargets).containsExactly("fork-a", "fork-b")
        }

        @Test
        fun `should default merge strategy to COLLECT_ALL`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.mergeStrategy).isEqualTo(MergeStrategy.COLLECT_ALL)
        }

        @Test
        fun `should set merge strategy to CONCATENATE`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.CONCATENATE
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.mergeStrategy).isEqualTo(MergeStrategy.CONCATENATE)
        }

        @Test
        fun `should set merge strategy to FIRST_SUCCESSFUL`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.FIRST_SUCCESSFUL
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.mergeStrategy).isEqualTo(MergeStrategy.FIRST_SUCCESSFUL)
        }

        @Test
        fun `should set merge strategy to MERGE_MAPS`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.MERGE_MAPS
                writes("metric_a", "metric_b")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.mergeStrategy).isEqualTo(MergeStrategy.MERGE_MAPS)
        }

        @Test
        fun `should set writes`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("merged_results")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.writes).containsExactly("merged_results")
        }

        @Test
        fun `should accept multiple writes for MERGE_MAPS`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.MERGE_MAPS
                writes("code_metrics", "docs_quality", "community_health")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.writes)
                .containsExactly("code_metrics", "docs_quality", "community_health")
        }

        @Test
        fun `should reject multiple writes for COLLECT_ALL`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("a", "b")
                onSuccess goto "next"
            }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject multiple writes for FIRST_SUCCESSFUL`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.FIRST_SUCCESSFUL
                writes("a", "b")
                onSuccess goto "next"
            }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject multiple writes for CONCATENATE`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.CONCATENATE
                writes("a", "b")
                onSuccess goto "next"
            }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject empty writes`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                onSuccess goto "next"
            }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must declare writes()")
        }

        @Test
        fun `should set timeout`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                timeout = 30000L
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.timeoutMs).isEqualTo(30000L)
        }

        @Test
        fun `should default timeout to zero`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.timeoutMs).isEqualTo(0L)
        }

        @Test
        fun `should set failOnError`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                failOnError = false
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.isFailOnAnyError).isFalse()
        }

        @Test
        fun `should default failOnError to true`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.isFailOnAnyError).isTrue()
        }

        @Test
        fun `should add onSuccess transition`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "process"
            }
            val node = builder.build()

            // Then
            val successTransition =
                node.transitionRules.filterIsInstance<SuccessTransition>().first()
            assertThat(successTransition.targetNode).isEqualTo("process")
        }

        @Test
        fun `should add onFailure with retry transition`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "process"
                onFailure retry 0 otherwise "error-handler"
            }
            val node = builder.build()

            // Then
            val failureTransition =
                node.transitionRules.filterIsInstance<FailureTransition>().first()
            assertThat(failureTransition.retryCount).isEqualTo(0)
            assertThat(failureTransition.thenTargetNode).isEqualTo("error-handler")
        }

        @Test
        fun `should set exports whitelist`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                exports("finding_a", "finding_b")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.exports).containsExactly("finding_a", "finding_b")
        }

        @Test
        fun `should default exports to empty list`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            // When
            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            // Then
            assertThat(node.exports).isEmpty()
        }

        @Test
        fun `should throw when no await targets defined`() {
            // Given
            val builder = JoinNodeBuilder("join-1")

            builder.apply { onSuccess goto "next" }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one await target")
        }
    }
}
