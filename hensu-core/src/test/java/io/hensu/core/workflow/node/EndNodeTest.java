package io.hensu.core.workflow.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExitStatus;
import org.junit.jupiter.api.Test;

class EndNodeTest {

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        assertThatThrownBy(() -> EndNode.builder().status(ExitStatus.SUCCESS).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowExceptionWhenStatusIsNull() {
        assertThatThrownBy(() -> EndNode.builder().id("test-node").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status");
    }
}
