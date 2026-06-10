package io.hensu.dsl.builders

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SubWorkflowNodeBuilderTest {

    @Test
    fun `should fail fast at DSL level when target is missing`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        assertThatThrownBy { builder.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("target is required")
    }

    @Test
    fun `should reject engine variable name in imports`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        assertThatThrownBy { builder.imports("score") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved engine variable")
    }

    @Test
    fun `should reject engine variable name in writes`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        assertThatThrownBy { builder.writes("approved") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved engine variable")
    }
}
