package io.hensu.dsl.builders

import io.hensu.core.agent.AgentConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AgentBuilderTest {
    @Nested
    inner class AgentRegistryBuilderTest {
        @Test
        fun `should register agent with all fields`() {
            // Given
            val agents = mutableMapOf<String, AgentConfig>()
            val registry = AgentRegistryBuilder(agents)

            // When
            registry.agent("writer") {
                role = "You are a writer"
                model = "claude-sonnet-4"
                temperature = 0.9
                maxTokens = 4000
                tools = listOf("search", "code")
            }

            // Then
            assertThat(agents).hasSize(1)
            assertThat(agents["writer"]).isNotNull

            val config = agents["writer"]!!
            assertThat(config.id).isEqualTo("writer")
            assertThat(config.role).isEqualTo("You are a writer")
            assertThat(config.model).isEqualTo("claude-sonnet-4")
            assertThat(config.temperature).isEqualTo(0.9)
            assertThat(config.maxTokens).isEqualTo(4000)
            assertThat(config.tools).containsExactly("search", "code")
        }

        @Test
        fun `should register multiple agents`() {
            // Given
            val agents = mutableMapOf<String, AgentConfig>()
            val registry = AgentRegistryBuilder(agents)

            // When
            registry.agent("writer") {
                role = "Writer"
                model = "claude-sonnet-4"
            }
            registry.agent("reviewer") {
                role = "Reviewer"
                model = "gpt-4"
            }

            // Then
            assertThat(agents).hasSize(2)
            assertThat(agents).containsKeys("writer", "reviewer")
        }
    }

    @Nested
    inner class BasicPropertiesTest {
        @Test
        fun `should build agent with required fields`() {
            // Given
            val builder = AgentBuilder("test-agent")

            // When
            builder.apply {
                role = "Test role"
                model = "claude-sonnet-4"
            }
            val config = builder.build()

            // Then
            assertThat(config.id).isEqualTo("test-agent")
            assertThat(config.role).isEqualTo("Test role")
            assertThat(config.model).isEqualTo("claude-sonnet-4")
        }

        @Test
        fun `should use default temperature of 0_7`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"

            // When
            val config = builder.build()

            // Then
            assertThat(config.temperature).isEqualTo(0.7)
        }

        @Test
        fun `should allow custom temperature`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.temperature = 0.5

            // When
            val config = builder.build()

            // Then
            assertThat(config.temperature).isEqualTo(0.5)
        }

        @Test
        fun `should set max tokens`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.maxTokens = 8000

            // When
            val config = builder.build()

            // Then
            assertThat(config.maxTokens).isEqualTo(8000)
        }

        @Test
        fun `should set tools list`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.tools = listOf("search", "calculator", "code")

            // When
            val config = builder.build()

            // Then
            assertThat(config.tools).containsExactly("search", "calculator", "code")
        }
    }

    @Nested
    inner class LangChain4jFieldsTest {
        @Test
        fun `should set maintainContext`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.maintainContext = true

            // When
            val config = builder.build()

            // Then
            assertThat(config.isMaintainContext).isTrue()
        }

        @Test
        fun `should default maintainContext to false`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"

            // When
            val config = builder.build()

            // Then
            assertThat(config.isMaintainContext).isFalse()
        }

        @Test
        fun `should set instructions`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.instructions = "Follow coding best practices"

            // When
            val config = builder.build()

            // Then
            assertThat(config.instructions).isEqualTo("Follow coding best practices")
        }

        @Test
        fun `should set topP`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.topP = 0.95

            // When
            val config = builder.build()

            // Then
            assertThat(config.topP).isEqualTo(0.95)
        }

        @Test
        fun `should set frequencyPenalty`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "gpt-4"
            builder.frequencyPenalty = 0.5

            // When
            val config = builder.build()

            // Then
            assertThat(config.frequencyPenalty).isEqualTo(0.5)
        }

        @Test
        fun `should set presencePenalty`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "gpt-4"
            builder.presencePenalty = 0.3

            // When
            val config = builder.build()

            // Then
            assertThat(config.presencePenalty).isEqualTo(0.3)
        }

        @Test
        fun `should set timeout`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test"
            builder.model = "claude-sonnet-4"
            builder.timeout = 60L

            // When
            val config = builder.build()

            // Then
            assertThat(config.timeout).isEqualTo(60L)
        }
    }

    @Nested
    inner class ValidationTest {
        @Test
        fun `should throw when role is blank`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.model = "claude-sonnet-4"

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Agent role is required")
        }

        @Test
        fun `should throw when model is blank`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "Test role"

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Agent model is required")
        }

        @Test
        fun `should throw when role is whitespace only`() {
            // Given
            val builder = AgentBuilder("test-agent")
            builder.role = "   "
            builder.model = "claude-sonnet-4"

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
