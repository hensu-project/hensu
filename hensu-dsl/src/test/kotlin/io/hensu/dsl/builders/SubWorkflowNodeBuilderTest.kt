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

    // Mirroring a gap key back would overwrite the parent's own record with the child's, hiding
    // whichever run was actually refused. `imports` needs no matching guard: an imported name must
    // already appear in the parent's state {} block, which an engine-owned key never does.
    @Test
    fun `should reject capability gap key in writes`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        assertThatThrownBy { builder.writes("_capability_gaps") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "subWorkflow('delegate_summary'): writes field '_capability_gaps' is written by " +
                    "the engine \u2013 it records tool refusals there. Route on it with " +
                    "onCondition(\"_capability_gap_count\"); never declare it."
            )
    }
}
