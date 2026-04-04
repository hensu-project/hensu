package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ParallelNodeTest {

    @Test
    void shouldThrowWhenNoBranches() {
        assertThatThrownBy(() -> ParallelNode.builder("parallel-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one branch");
    }

    @Test
    void shouldMakeBranchesListImmutable() {
        ParallelNode node =
                ParallelNode.builder("parallel-1").branch("b1", "agent-1", "Prompt 1").build();

        assertThat(node.getBranchesList()).isUnmodifiable();
    }
}
