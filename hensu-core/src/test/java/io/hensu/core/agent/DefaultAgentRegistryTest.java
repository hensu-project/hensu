package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse.TextResponse;
import io.hensu.core.agent.stub.StubAgentProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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

    @Test
    void shouldReplaceExistingAgentOnReRegistration() {
        AgentConfig config1 = createConfig("model-1");
        AgentConfig config2 = createConfig("model-2");
        when(agentFactory.createAgent(eq("writer"), any(AgentConfig.class)))
                .thenReturn(mockAgent)
                .thenReturn(mockAgent2);

        registry.registerAgent("writer", config1);
        Agent result = registry.registerAgent("writer", config2);

        assertThat(result).isSameAs(mockAgent2);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void shouldThrowAgentNotFoundExceptionWithAgentIdInMessage() {
        assertThatThrownBy(() -> registry.getAgentOrThrow("no-such-agent"))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("no-such-agent");
    }

    @Test
    void getAgentIdsShouldReturnUnmodifiableSet() {
        var ids = registry.getAgentIds();
        assertThatThrownBy(() -> ids.add("intruder"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldThrowWhenNoProviderSupportsModel() {
        AgentFactory realFactory = new AgentFactory(Map.of(), List.of());
        DefaultAgentRegistry realRegistry = new DefaultAgentRegistry(realFactory);

        AgentConfig config = createConfig("unknown-model");

        assertThatThrownBy(() -> realRegistry.registerAgent("writer", config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown-model");
    }

    @Nested
    class RealIntegration {

        @BeforeEach
        void enableStubMode() {
            System.setProperty("hensu.stub.enabled", "true");
        }

        @AfterEach
        void disableStubMode() {
            System.clearProperty("hensu.stub.enabled");
        }

        @Test
        void shouldWireRegistryFactoryProviderChainAndReturnStubResponse() {
            // Verifies the full registry → factory → provider chain.
            // If AgentFactory constructor changes or StubAgentProvider is broken,
            // this is the first test to fail.
            AgentFactory realFactory = new AgentFactory(Map.of(), List.of(new StubAgentProvider()));
            DefaultAgentRegistry realRegistry = new DefaultAgentRegistry(realFactory);

            AgentConfig config =
                    AgentConfig.builder().id("writer").role("assistant").model("any-model").build();

            Agent agent = realRegistry.registerAgent("writer", config);
            assertThat(agent.getId()).isEqualTo("writer");

            AgentResponse response = agent.execute("Hello", Map.of());

            assertThat(response).isInstanceOf(TextResponse.class);
            TextResponse text = (TextResponse) response;
            assertThat(text.content()).startsWith("[STUB]");
            assertThat(text.metadata()).containsEntry("stub", true);
            assertThat(text.metadata()).containsEntry("model", "any-model");
        }
    }

    private AgentConfig createConfig(String model) {
        return AgentConfig.builder().id("writer").role("assistant").model(model).build();
    }
}
