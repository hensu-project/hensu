package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericNodeTest {

    @Test
    void shouldThrowWhenIdIsNull() {
        assertThatThrownBy(() -> GenericNode.builder().executorType("validator").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    void shouldThrowWhenIdIsBlank() {
        assertThatThrownBy(() -> GenericNode.builder().id("   ").executorType("validator").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    void shouldThrowWhenExecutorTypeIsNull() {
        assertThatThrownBy(() -> GenericNode.builder().id("generic-1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executorType is required");
    }

    @Test
    void shouldThrowWhenExecutorTypeIsBlank() {
        assertThatThrownBy(() -> GenericNode.builder().id("generic-1").executorType("").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executorType is required");
    }

    @Test
    void shouldMakeConfigImmutable() {
        GenericNode node =
                GenericNode.builder()
                        .id("generic-1")
                        .executorType("validator")
                        .config(Map.of("key", "value"))
                        .build();

        assertThat(node.getConfig()).isUnmodifiable();
    }
}
