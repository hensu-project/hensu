package io.hensu.dsl.builders

import io.hensu.core.workflow.node.NodeType
import io.hensu.core.workflow.transition.FailureTransition
import io.hensu.core.workflow.transition.SuccessTransition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SubWorkflowNodeBuilderTest {

    @Test
    fun `should build node with identity mappings and round-trip all optional fields`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")
        builder.apply {
            target = "sub-summarizer"
            targetVersion = "1.0.0"
            imports("draft")
            writes("tl_dr")
            onSuccess goto "publish"
            onFailure goto "fallback"
        }

        val node = builder.build()

        assertThat(node.id).isEqualTo("delegate_summary")
        assertThat(node.nodeType).isEqualTo(NodeType.SUB_WORKFLOW)
        assertThat(node.workflowId).isEqualTo("sub-summarizer")
        assertThat(node.targetVersion).isEqualTo("1.0.0")
        // Same-name discipline: both mappings must be identity. Any future "optimization"
        // that turns `associateWith { it }` into an asymmetric or empty map breaks the
        // parent↔child variable contract silently.
        assertThat(node.inputMapping).containsExactlyEntriesOf(mapOf("draft" to "draft"))
        assertThat(node.outputMapping).containsExactlyEntriesOf(mapOf("tl_dr" to "tl_dr"))
        assertThat(node.transitionRules).hasAtLeastOneElementOfType(SuccessTransition::class.java)
        assertThat(node.transitionRules).hasAtLeastOneElementOfType(FailureTransition::class.java)
    }

    @Test
    fun `should fail fast at DSL level when target is missing`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        // Without the DSL guard the user sees the core builder's generic "workflowId is
        // required" message far from the subWorkflow{} block, which is exactly what this
        // contextual message prevents.
        assertThatThrownBy { builder.build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("target is required")
    }

    @Test
    fun `should reject engine variable name in imports`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        // Importing 'score' would pre-populate the child's consensus-scoring variable
        // from the parent, corrupting any downstream ScoreTransition or consensus gate.
        assertThatThrownBy { builder.imports("score") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved engine variable")
    }

    @Test
    fun `should reject engine variable name in writes`() {
        val builder = SubWorkflowNodeBuilder("delegate_summary")

        // Mirror-back direction: child's 'approved' must not leak into parent, where
        // it would drive an ApprovalTransition the parent never declared.
        assertThatThrownBy { builder.writes("approved") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved engine variable")
    }
}
