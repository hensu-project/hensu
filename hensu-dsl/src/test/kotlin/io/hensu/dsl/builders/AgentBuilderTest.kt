package io.hensu.dsl.builders

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentBuilderTest {

    @Nested
    inner class DefaultValues {
        @Test
        fun `should use default temperature of 0_7`() {
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"

            val config = builder.build()

            assertThat(config.temperature).isEqualTo(0.7)
        }

        @Test
        fun `should default maintainContext to false`() {
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"

            val config = builder.build()

            assertThat(config.isMaintainContext).isFalse()
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `should throw when role is blank`() {
            val builder = AgentBuilder("test-agent")
            builder.model = "claude-sonnet-4"

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Agent role is required")
        }

        @Test
        fun `should throw when model is blank`() {
            val builder = AgentBuilder("test-agent")
            builder.role = "Test role"

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Agent model is required")
        }

        @Test
        fun `should throw when role is whitespace only`() {
            val builder = AgentBuilder("test-agent")
            builder.role = "   "
            builder.model = "claude-sonnet-4"

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
