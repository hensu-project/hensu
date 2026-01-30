package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopNodeTest {

    @Test
    void shouldCreateLoopNodeWithId() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getId()).isEqualTo("loop-1");
        assertThat(node.getNodeType()).isEqualTo(NodeType.LOOP);
    }

    @Test
    void shouldReturnEmptyRubricId() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getRubricId()).isEmpty();
    }

    @Test
    void shouldReturnDefaultMaxIterations() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getMaxIterations()).isEqualTo(0);
    }

    @Test
    void shouldReturnNullBodyNode() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getBodyNode()).isNull();
    }

    @Test
    void shouldReturnNullBreakRules() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getBreakRules()).isNull();
    }

    @Test
    void shouldReturnNullCondition() {
        // When
        LoopNode node = new LoopNode("loop-1");

        // Then
        assertThat(node.getCondition()).isNull();
    }
}
