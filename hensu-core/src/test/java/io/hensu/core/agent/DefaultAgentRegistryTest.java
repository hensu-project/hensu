package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultAgentRegistryTest {

    @Mock private AgentFactory agentFactory;

    @Mock private Agent mockAgent;

    @Mock private Agent mockAgent2;

    private DefaultAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry(agentFactory);
    }

    @Nested
    class RegisterAgentTest {

        @Test
        void shouldRegisterAgentWithConfig() {
            // Given
            AgentConfig config = createConfig("writer", "claude-sonnet-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);

            // When
            Agent result = registry.registerAgent("writer", config);

            // Then
            assertThat(result).isSameAs(mockAgent);
            assertThat(registry.hasAgent("writer")).isTrue();
            verify(agentFactory).createAgent("writer", config);
        }

        @Test
        void shouldReplaceExistingAgent() {
            // Given
            AgentConfig config1 = createConfig("writer", "claude-sonnet-4");
            AgentConfig config2 = createConfig("writer", "gpt-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent)
                    .thenReturn(mockAgent2);

            // When
            registry.registerAgent("writer", config1);
            Agent result = registry.registerAgent("writer", config2);

            // Then
            assertThat(result).isSameAs(mockAgent2);
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        void shouldRegisterMultipleAgents() {
            // Given
            AgentConfig config1 = createConfig("writer", "claude-sonnet-4");
            AgentConfig config2 = createConfig("reviewer", "gpt-4");
            Map<String, AgentConfig> configs =
                    Map.of(
                            "writer", config1,
                            "reviewer", config2);
            when(agentFactory.createAgent(anyString(), any(AgentConfig.class)))
                    .thenReturn(mockAgent);

            // When
            registry.registerAgents(configs);

            // Then
            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.hasAgent("writer")).isTrue();
            assertThat(registry.hasAgent("reviewer")).isTrue();
        }
    }

    @Nested
    class GetAgentTest {

        @Test
        void shouldReturnAgentWhenExists() {
            // Given
            AgentConfig config = createConfig("writer", "claude-sonnet-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", config);

            // When
            Optional<Agent> result = registry.getAgent("writer");

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isSameAs(mockAgent);
        }

        @Test
        void shouldReturnEmptyWhenAgentNotFound() {
            // When
            Optional<Agent> result = registry.getAgent("unknown");

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void shouldThrowWhenAgentNotFoundWithGetOrThrow() {
            // When/Then
            assertThatThrownBy(() -> registry.getAgentOrThrow("unknown"))
                    .isInstanceOf(AgentNotFoundException.class)
                    .hasMessageContaining("unknown");
        }

        @Test
        void shouldReturnAgentWithGetOrThrow() throws AgentNotFoundException {
            // Given
            AgentConfig config = createConfig("writer", "claude-sonnet-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", config);

            // When
            Agent result = registry.getAgentOrThrow("writer");

            // Then
            assertThat(result).isSameAs(mockAgent);
        }
    }

    @Nested
    class UnregisterAgentTest {

        @Test
        void shouldUnregisterExistingAgent() {
            // Given
            AgentConfig config = createConfig("writer", "claude-sonnet-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", config);

            // When
            boolean result = registry.unregisterAgent("writer");

            // Then
            assertThat(result).isTrue();
            assertThat(registry.hasAgent("writer")).isFalse();
        }

        @Test
        void shouldReturnFalseWhenUnregisteringNonExistentAgent() {
            // When
            boolean result = registry.unregisterAgent("unknown");

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    class HasAgentTest {

        @Test
        void shouldReturnTrueForExistingAgent() {
            // Given
            AgentConfig config = createConfig("writer", "claude-sonnet-4");
            when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", config);

            // When/Then
            assertThat(registry.hasAgent("writer")).isTrue();
        }

        @Test
        void shouldReturnFalseForNonExistentAgent() {
            // When/Then
            assertThat(registry.hasAgent("unknown")).isFalse();
        }
    }

    @Nested
    class GetAgentIdsTest {

        @Test
        void shouldReturnAllAgentIds() {
            // Given
            when(agentFactory.createAgent(anyString(), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", createConfig("writer", "claude-sonnet-4"));
            registry.registerAgent("reviewer", createConfig("reviewer", "gpt-4"));

            // When
            var ids = registry.getAgentIds();

            // Then
            assertThat(ids).containsExactlyInAnyOrder("writer", "reviewer");
        }

        @Test
        void shouldReturnEmptySetWhenNoAgents() {
            // When
            var ids = registry.getAgentIds();

            // Then
            assertThat(ids).isEmpty();
        }

        @Test
        void shouldReturnUnmodifiableSet() {
            // When
            var ids = registry.getAgentIds();

            // Then
            assertThatThrownBy(() -> ids.add("new-agent"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class ClearTest {

        @Test
        void shouldClearAllAgents() {
            // Given
            when(agentFactory.createAgent(anyString(), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("writer", createConfig("writer", "claude-sonnet-4"));
            registry.registerAgent("reviewer", createConfig("reviewer", "gpt-4"));

            // When
            registry.clear();

            // Then
            assertThat(registry.size()).isEqualTo(0);
            assertThat(registry.hasAgent("writer")).isFalse();
            assertThat(registry.hasAgent("reviewer")).isFalse();
        }
    }

    @Nested
    class SizeTest {

        @Test
        void shouldReturnZeroWhenEmpty() {
            // When/Then
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        void shouldReturnCorrectCount() {
            // Given
            when(agentFactory.createAgent(anyString(), any(AgentConfig.class)))
                    .thenReturn(mockAgent);
            registry.registerAgent("agent1", createConfig("agent1", "model"));
            registry.registerAgent("agent2", createConfig("agent2", "model"));
            registry.registerAgent("agent3", createConfig("agent3", "model"));

            // When/Then
            assertThat(registry.size()).isEqualTo(3);
        }
    }

    @Test
    void shouldReturnAgentFactory() {
        // When
        AgentFactory result = registry.getAgentFactory();

        // Then
        assertThat(result).isSameAs(agentFactory);
    }

    private AgentConfig createConfig(String id, String model) {
        return AgentConfig.builder().id(id).role("assistant").model(model).build();
    }
}
