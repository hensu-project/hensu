package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ForkNodeTest {

    @Test
    void shouldThrowWhenNoTargets() {
        assertThatThrownBy(() -> ForkNode.builder("fork-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one target");
    }

    @Test
    void shouldMakeTargetsImmutable() {
        ForkNode node = ForkNode.builder("fork-1").targets("node-a", "node-b").build();

        assertThat(node.getTargets()).isUnmodifiable();
    }

    @Test
    void shouldMakeTargetConfigsImmutable() {
        ForkNode node =
                ForkNode.builder("fork-1")
                        .targets("node-a")
                        .targetConfigs(Map.of("node-a", "config"))
                        .build();

        assertThat(node.getTargetConfigs()).isUnmodifiable();
    }
}
