package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.agent.stub.StubAgentProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Provider selection: the stub provider claims the model `stub` by name, and every model
/// only in stub mode, so stubbed and live agents can share one environment.
@DisplayName("AgentFactory")
class AgentFactoryTest {

    @Test
    @DisplayName("keeps a live model on its real provider beside a stubbed agent")
    void shouldRouteMixedModelsToTheirOwnProviders() {
        RealProvider real = new RealProvider();
        AgentFactory factory =
                new AgentFactory(Map.of(), List.of(real, new StubAgentProvider(false)));

        Agent writer = factory.createAgent("writer", config(StubAgentProvider.MODEL));
        Agent reviewer = factory.createAgent("reviewer", config("real-model"));

        assertThat(writer).isInstanceOf(StubAgent.class);
        assertThat(reviewer).isSameAs(real.agent);
    }

    @Test
    @DisplayName("stub mode intercepts every model, outranking real providers")
    void shouldInterceptEveryModelInStubMode() {
        AgentFactory factory =
                new AgentFactory(
                        Map.of(), List.of(new RealProvider(), new StubAgentProvider(true)));

        assertThat(factory.createAgent("reviewer", config("real-model")))
                .isInstanceOf(StubAgent.class);
    }

    @Test
    @DisplayName("names the declared model and what each loaded provider accepts")
    void shouldDescribeEveryProviderWhenNoneSupportsTheModel() {
        AgentFactory factory =
                new AgentFactory(
                        Map.of(), List.of(new RealProvider(), new StubAgentProvider(false)));

        assertThatThrownBy(() -> factory.createAgent("writer", config("unknown-model")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'unknown-model'")
                .hasMessageContaining("real accepts [real-*]")
                .hasMessageContaining("stub accepts [stub]");
    }

    private static AgentConfig config(String model) {
        return AgentConfig.builder().id("agent").role("assistant").model(model).build();
    }

    /// Stands in for a vendor provider such as LangChain4j: claims `real-*` at priority 100.
    private static final class RealProvider implements AgentProvider {

        private final Agent agent = mock(Agent.class);

        @Override
        public String getName() {
            return "real";
        }

        @Override
        public boolean supportsModel(String modelName) {
            return modelName.startsWith("real-");
        }

        @Override
        public String supportedModels() {
            return "real-*";
        }

        @Override
        public Agent createAgent(
                String agentId, AgentConfig config, Map<String, String> credentials) {
            return agent;
        }

        @Override
        public int getPriority() {
            return 100;
        }
    }
}
