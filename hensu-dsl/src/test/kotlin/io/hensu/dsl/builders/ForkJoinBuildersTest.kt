package io.hensu.dsl.builders

import io.hensu.core.workflow.node.MergeStrategy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ForkJoinBuildersTest {
    @Nested
    inner class ForkNodeDefaults {
        @Test
        fun `should default waitAll to false`() {
            val builder = ForkNodeBuilder("fork-1")

            builder.apply {
                targets("task-a")
                onComplete goto "join"
            }
            val node = builder.build()

            assertThat(node.isWaitForAll).isFalse()
        }
    }

    @Nested
    inner class ForkNodeValidation {
        @Test
        fun `should throw when no targets defined`() {
            val builder = ForkNodeBuilder("fork-1")

            builder.apply { onComplete goto "join" }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one target")
        }
    }

    @Nested
    inner class JoinNodeDefaults {
        @Test
        fun `should default merge strategy to COLLECT_ALL`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            assertThat(node.mergeStrategy).isEqualTo(MergeStrategy.COLLECT_ALL)
        }

        @Test
        fun `should default timeout to zero`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            assertThat(node.timeoutMs).isEqualTo(0L)
        }

        @Test
        fun `should default failOnError to true`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            assertThat(node.isFailOnAnyError).isTrue()
        }

        @Test
        fun `should default exports to empty list`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("result")
                onSuccess goto "next"
            }
            val node = builder.build()

            assertThat(node.exports).isEmpty()
        }
    }

    @Nested
    inner class JoinNodeValidation {
        @Test
        fun `should reject multiple writes for COLLECT_ALL`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                writes("a", "b")
                onSuccess goto "next"
            }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject multiple writes for FIRST_SUCCESSFUL`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.FIRST_SUCCESSFUL
                writes("a", "b")
                onSuccess goto "next"
            }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject multiple writes for CONCATENATE`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                mergeStrategy = MergeStrategy.CONCATENATE
                writes("a", "b")
                onSuccess goto "next"
            }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("exactly one writes()")
        }

        @Test
        fun `should reject empty writes`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply {
                await("fork-1")
                onSuccess goto "next"
            }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must declare writes()")
        }

        @Test
        fun `should throw when no await targets defined`() {
            val builder = JoinNodeBuilder("join-1")

            builder.apply { onSuccess goto "next" }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least one await target")
        }
    }
}
