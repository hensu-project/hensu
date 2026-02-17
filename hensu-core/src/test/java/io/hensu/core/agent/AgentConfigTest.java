package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void shouldBuildAgentConfigWithRequiredFields() {
        // When
        AgentConfig config =
                AgentConfig.builder()
                        .id("test-agent")
                        .role("assistant")
                        .model("claude-sonnet-4-20250514")
                        .build();

        // Then
        assertThat(config.getId()).isEqualTo("test-agent");
        assertThat(config.getRole()).isEqualTo("assistant");
        assertThat(config.getModel()).isEqualTo("claude-sonnet-4-20250514");
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // When/Then
        assertThatThrownBy(() -> AgentConfig.builder().role("assistant").model("gpt-4").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Agent ID required");
    }

    @Test
    void shouldThrowExceptionWhenRoleIsNull() {
        // When/Then
        assertThatThrownBy(() -> AgentConfig.builder().id("test-agent").model("gpt-4").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Role required");
    }

    @Test
    void shouldThrowExceptionWhenModelIsNull() {
        // When/Then
        assertThatThrownBy(() -> AgentConfig.builder().id("test-agent").role("assistant").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Model required");
    }
}
