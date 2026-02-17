package io.hensu.dsl.builders

import io.hensu.core.workflow.node.GenericNode
import io.hensu.core.workflow.node.NodeType
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.ScoreTransition
import io.hensu.core.workflow.transition.SuccessTransition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GenericNodeBuilderTest {
    @Nested
    inner class BasicPropertiesTest {
        @Test
        fun `should build generic node with executor type`() {
            // Given
            val builder = GenericNodeBuilder("validate-input")

            // When
            builder.apply {
                executorType = "validator"
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.id).isEqualTo("validate-input")
            assertThat(node.nodeType).isEqualTo(NodeType.GENERIC)
            assertThat(node.executorType).isEqualTo("validator")
        }

        @Test
        fun `should set rubric`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            // When
            builder.apply {
                executorType = "validator"
                rubric = "validation-quality"
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.rubricId).isEqualTo("validation-quality")
        }

        @Test
        fun `should throw when executor type is null`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            builder.apply { onSuccess goto "next" }

            // When/Then
            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("executorType")
        }
    }

    @Nested
    inner class ConfigTest {
        @Test
        fun `should build with config entries`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            // When
            builder.apply {
                executorType = "validator"
                config {
                    "minLength" to 10
                    "maxLength" to 1000
                    "allowEmpty" to false
                }
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.config).containsEntry("minLength", 10)
            assertThat(node.config).containsEntry("maxLength", 1000)
            assertThat(node.config).containsEntry("allowEmpty", false)
        }

        @Test
        fun `should support various config value types`() {
            // Given
            val builder = GenericNodeBuilder("transform")
            val nestedMap = mapOf("nested" to "value")

            // When
            builder.apply {
                executorType = "transformer"
                config {
                    "stringValue" to "hello"
                    "intValue" to 42
                    "doubleValue" to 3.14
                    "boolValue" to true
                    "listValue" to listOf("a", "b", "c")
                    "mapValue" to nestedMap
                }
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.config["stringValue"]).isEqualTo("hello")
            assertThat(node.config["intValue"]).isEqualTo(42)
            assertThat(node.config["doubleValue"]).isEqualTo(3.14)
            assertThat(node.config["boolValue"]).isEqualTo(true)
            assertThat(node.config["listValue"]).isEqualTo(listOf("a", "b", "c"))
            assertThat(node.config["mapValue"] as Map<String, String>)
                .containsEntry("nested", "value")
        }

        @Test
        fun `should work without config`() {
            // Given
            val builder = GenericNodeBuilder("simple")

            // When
            builder.apply {
                executorType = "simple-executor"
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.config).isEmpty()
        }

        @Test
        fun `should merge multiple config blocks`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            // When
            builder.apply {
                executorType = "validator"
                config { "key1" to "value1" }
                config { "key2" to "value2" }
                onSuccess goto "next"
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.config).containsEntry("key1", "value1")
            assertThat(node.config).containsEntry("key2", "value2")
        }
    }

    @Nested
    inner class TransitionTest {
        @Test
        fun `should add onSuccess transition`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            // When
            builder.apply {
                executorType = "validator"
                onSuccess goto "process"
            }
            val node = builder.build() as GenericNode

            // Then
            val successTransition =
                node.transitionRules.filterIsInstance<SuccessTransition>().first()
            assertThat(successTransition.targetNode).isEqualTo("process")
        }

        @Test
        fun `should add onFailure with retry transition`() {
            // Given
            val builder = GenericNodeBuilder("validate")

            // When
            builder.apply {
                executorType = "validator"
                onSuccess goto "process"
                onFailure retry 3 otherwise "error-handler"
            }
            val node = builder.build() as GenericNode

            // Then
            val failureTransition =
                node.transitionRules.filterIsInstance<FailureTransition>().first()
            assertThat(failureTransition.retryCount).isEqualTo(3)
            assertThat(failureTransition.thenTargetNode).isEqualTo("error-handler")
        }

        @Test
        fun `should add score transitions`() {
            // Given
            val builder = GenericNodeBuilder("evaluate")

            // When
            builder.apply {
                executorType = "evaluator"
                rubric = "quality"
                onScore {
                    whenScore greaterThanOrEqual 80.0 goto "approved"
                    whenScore lessThan 80.0 goto "rejected"
                }
            }
            val node = builder.build() as GenericNode

            // Then
            val scoreTransition = node.transitionRules.filterIsInstance<ScoreTransition>().first()
            assertThat(scoreTransition.conditions).hasSize(2)
        }

        @Test
        fun `should combine multiple transition types`() {
            // Given
            val builder = GenericNodeBuilder("complex")

            // When
            builder.apply {
                executorType = "complex-executor"
                onSuccess goto "process"
                onFailure retry 2 otherwise "fallback"
                onScore { whenScore greaterThan 90.0 goto "excellent" }
            }
            val node = builder.build() as GenericNode

            // Then
            assertThat(node.transitionRules).hasSize(3)
        }
    }

    @Nested
    inner class ConfigBuilderTest {
        @Test
        fun `should create config entries with infix to`() {
            // Given
            val configBuilder = ConfigBuilder()

            // When
            configBuilder.apply {
                "name" to "test"
                "count" to 5
            }

            // Then
            assertThat(configBuilder.entries).containsEntry("name", "test")
            assertThat(configBuilder.entries).containsEntry("count", 5)
        }
    }

    @Test
    fun `should allow multiple nodes with same executor type`() {
        // Given
        val builder1 = GenericNodeBuilder("validate-name")
        val builder2 = GenericNodeBuilder("validate-email")

        // When
        builder1.apply {
            executorType = "validator"
            config { "field" to "name" }
            onSuccess goto "next"
        }
        builder2.apply {
            executorType = "validator"
            config { "field" to "email" }
            onSuccess goto "next"
        }

        val node1 = builder1.build() as GenericNode
        val node2 = builder2.build() as GenericNode

        // Then
        assertThat(node1.executorType).isEqualTo(node2.executorType)
        assertThat(node1.id).isNotEqualTo(node2.id)
        assertThat(node1.config).isNotEqualTo(node2.config)
    }
}
