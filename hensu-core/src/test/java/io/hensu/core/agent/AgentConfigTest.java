package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void shouldThrowWhenIdIsNull() {
        assertThatThrownBy(() -> AgentConfig.builder().role("assistant").model("gpt-4").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Agent ID required");
    }

    @Test
    void shouldThrowWhenRoleIsNull() {
        assertThatThrownBy(() -> AgentConfig.builder().id("test-agent").model("gpt-4").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Role required");
    }

    @Test
    void shouldThrowWhenModelIsNull() {
        assertThatThrownBy(() -> AgentConfig.builder().id("test-agent").role("assistant").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Model required");
    }
}
