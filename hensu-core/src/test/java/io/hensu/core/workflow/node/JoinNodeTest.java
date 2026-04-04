package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JoinNodeTest {

    @Test
    void shouldThrowWhenNoAwaitTargets() {
        assertThatThrownBy(() -> JoinNode.builder("join-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one await target");
    }

    @Test
    void shouldMakeAwaitTargetsImmutable() {
        JoinNode node = JoinNode.builder("join-1").awaitTargets("fork-1", "fork-2").build();

        assertThat(node.getAwaitTargets()).isUnmodifiable();
    }
}
