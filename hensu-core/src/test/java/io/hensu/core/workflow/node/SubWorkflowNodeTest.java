package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SubWorkflowNodeTest {

    @Test
    void shouldThrowWhenIdMissing() {
        assertThatThrownBy(() -> SubWorkflowNode.builder().workflowId("nested").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowWhenWorkflowIdMissing() {
        assertThatThrownBy(() -> SubWorkflowNode.builder().id("sub-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflowId");
    }
}
