package io.hensu.dsl.builders

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GenericNodeBuilderTest {

    @Nested
    inner class DefaultValues {
        @Test
        fun `should default config to empty`() {
            val builder = GenericNodeBuilder("simple")

            builder.apply {
                executorType = "simple-executor"
                onSuccess goto "next"
            }
            val node = builder.build() as io.hensu.core.workflow.node.GenericNode

            assertThat(node.config).isEmpty()
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `should throw when executor type is null`() {
            val builder = GenericNodeBuilder("validate")

            builder.apply { onSuccess goto "next" }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("executorType")
        }
    }
}
