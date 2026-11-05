package io.hensu.cli.producers;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.HensuEnvironment;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.dsl.builders.Models;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// `hensu.stub.enabled=true` in the CLI's config reaches the stub provider through the
/// credentials map the producer builds, not through a JVM-wide system property, so stub mode
/// stays scoped to the environment that asked for it.
@QuarkusTest
@TestProfile(StubModeWiringTest.StubModeProfile.class)
class StubModeWiringTest {

    @Inject HensuEnvironment environment;

    @Test
    void shouldStubALiveModelWhenTheConfigEnablesStubMode() {
        AgentConfig config =
                AgentConfig.builder()
                        .id("stub-mode-reviewer")
                        .role("reviewer")
                        .model(Models.GEMINI_3_1_FLASH_LITE)
                        .build();

        assertThat(environment.getAgentRegistry().registerAgent(config.getId(), config))
                .isInstanceOf(StubAgent.class);
        assertThat(System.getProperty("hensu.stub.enabled")).isNull();
    }

    public static class StubModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("hensu.stub.enabled", "true");
        }
    }
}
