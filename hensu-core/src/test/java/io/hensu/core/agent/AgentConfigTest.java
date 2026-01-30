package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
    void shouldUseDefaultTemperature() {
        // When
        AgentConfig config =
                AgentConfig.builder().id("test-agent").role("assistant").model("gpt-4").build();

        // Then
        assertThat(config.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void shouldBuildAgentConfigWithAllFields() {
        // When
        AgentConfig config =
                AgentConfig.builder()
                        .id("full-agent")
                        .role("code reviewer")
                        .model("claude-opus-4-20250514")
                        .temperature(0.3)
                        .maxTokens(4096)
                        .tools(List.of("code_search", "file_read"))
                        .maintainContext(true)
                        .instructions("Be thorough and precise")
                        .topP(0.95)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .timeout(120L)
                        .build();

        // Then
        assertThat(config.getId()).isEqualTo("full-agent");
        assertThat(config.getRole()).isEqualTo("code reviewer");
        assertThat(config.getModel()).isEqualTo("claude-opus-4-20250514");
        assertThat(config.getTemperature()).isEqualTo(0.3);
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getTools()).containsExactly("code_search", "file_read");
        assertThat(config.isMaintainContext()).isTrue();
        assertThat(config.getInstructions()).isEqualTo("Be thorough and precise");
        assertThat(config.getTopP()).isEqualTo(0.95);
        assertThat(config.getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(config.getPresencePenalty()).isEqualTo(0.3);
        assertThat(config.getTimeout()).isEqualTo(120L);
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

    @Test
    void shouldReturnEmptyToolsListByDefault() {
        // When
        AgentConfig config =
                AgentConfig.builder().id("test-agent").role("assistant").model("gpt-4").build();

        // Then
        assertThat(config.getTools()).isEmpty();
    }

    @Test
    void shouldMakeToolsListImmutable() {
        // When
        AgentConfig config =
                AgentConfig.builder()
                        .id("test-agent")
                        .role("assistant")
                        .model("gpt-4")
                        .tools(List.of("tool1"))
                        .build();

        // Then
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> config.getTools().add("tool2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldNotMaintainContextByDefault() {
        // When
        AgentConfig config =
                AgentConfig.builder().id("test-agent").role("assistant").model("gpt-4").build();

        // Then
        assertThat(config.isMaintainContext()).isFalse();
    }

    @Test
    void shouldReturnNullForOptionalFieldsWhenNotSet() {
        // When
        AgentConfig config =
                AgentConfig.builder().id("test-agent").role("assistant").model("gpt-4").build();

        // Then
        assertThat(config.getMaxTokens()).isNull();
        assertThat(config.getInstructions()).isNull();
        assertThat(config.getTopP()).isNull();
        assertThat(config.getFrequencyPenalty()).isNull();
        assertThat(config.getPresencePenalty()).isNull();
        assertThat(config.getTimeout()).isNull();
    }

    @Test
    void shouldImplementEqualsBasedOnId() {
        // Given
        AgentConfig config1 =
                AgentConfig.builder().id("agent-1").role("assistant").model("gpt-4").build();

        AgentConfig config2 =
                AgentConfig.builder()
                        .id("agent-1")
                        .role("different-role")
                        .model("different-model")
                        .build();

        AgentConfig config3 =
                AgentConfig.builder().id("agent-2").role("assistant").model("gpt-4").build();

        // Then
        assertThat(config1).isEqualTo(config2); // Same ID
        assertThat(config1).isNotEqualTo(config3); // Different ID
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        AgentConfig config =
                AgentConfig.builder().id("test-agent").role("assistant").model("gpt-4").build();

        // When
        String toString = config.toString();

        // Then
        assertThat(toString).contains("test-agent");
        assertThat(toString).contains("gpt-4");
    }
}
