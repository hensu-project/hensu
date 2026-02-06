package io.hensu.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.rubric.RubricRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HensuFactoryTest {

    private HensuEnvironment environment;

    @Mock private NodeExecutorRegistry mockNodeExecutorRegistry;

    @Mock private AgentRegistry mockAgentRegistry;

    @Mock private ExecutorService mockExecutorService;

    @AfterEach
    void tearDown() {
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    void shouldCreateEnvironmentWithDefaultConfig() {
        // When
        environment = HensuFactory.createEnvironment();

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getWorkflowExecutor()).isNotNull();
        assertThat(environment.getAgentRegistry()).isNotNull();
        assertThat(environment.getNodeExecutorRegistry()).isNotNull();
        assertThat(environment.getRubricRepository()).isNotNull();
    }

    @Test
    void shouldCreateEnvironmentWithCustomConfig() {
        // Given
        HensuConfig config =
                HensuConfig.builder().useVirtualThreads(false).threadPoolSize(5).build();

        // When
        environment = HensuFactory.createEnvironment(config);

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getWorkflowExecutor()).isNotNull();
    }

    @Test
    void shouldCreateEnvironmentWithCustomCredentials() {
        // Given
        HensuConfig config = new HensuConfig();
        Map<String, String> credentials = new HashMap<>();
        credentials.put("ANTHROPIC_API_KEY", "test-key");

        // When
        environment = HensuFactory.createEnvironment(config, credentials);

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getAgentRegistry()).isNotNull();
    }

    @Test
    void shouldCreateEnvironmentWithCustomNodeExecutorRegistry() {
        // Given
        HensuConfig config = new HensuConfig();

        // When
        environment =
                HensuFactory.createEnvironment(
                        config, mockNodeExecutorRegistry, mockAgentRegistry, mockExecutorService);

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getNodeExecutorRegistry()).isSameAs(mockNodeExecutorRegistry);
    }

    @Test
    void shouldCreateEnvironmentWithCustomAgentRegistry() {
        // Given
        HensuConfig config = new HensuConfig();

        // When
        environment =
                HensuFactory.createEnvironment(
                        config, mockNodeExecutorRegistry, mockAgentRegistry, mockExecutorService);

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getAgentRegistry()).isSameAs(mockAgentRegistry);
    }

    @Test
    void shouldCreateBuilderWithFluentApi() {
        // When
        HensuFactory.Builder builder = HensuFactory.builder();

        // Then
        assertThat(builder).isNotNull();
    }

    @Test
    void shouldBuildEnvironmentWithBuilder() {
        // When
        environment = HensuFactory.builder().config(new HensuConfig()).build();

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getWorkflowExecutor()).isNotNull();
    }

    @Test
    void shouldBuildEnvironmentWithCredentials() {
        // When
        environment =
                HensuFactory.builder()
                        .anthropicApiKey("test-anthropic-key")
                        .openAiApiKey("test-openai-key")
                        .build();

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getAgentRegistry()).isNotNull();
        assertThat(environment.getNodeExecutorRegistry()).isNotNull();
    }

    @Test
    void shouldBuildEnvironmentWithSingleCredential() {
        // When
        environment = HensuFactory.builder().credential("CUSTOM_KEY", "custom-value").build();

        // Then
        assertThat(environment).isNotNull();
    }

    @Test
    void shouldBuildEnvironmentWithCredentialsMap() {
        // Given
        Map<String, String> credentials = new HashMap<>();
        credentials.put("ANTHROPIC_API_KEY", "test-key");
        credentials.put("OPENAI_API_KEY", "openai-key");

        // When
        environment = HensuFactory.builder().credentials(credentials).build();

        // Then
        assertThat(environment).isNotNull();
    }

    @Test
    void shouldBuildEnvironmentWithCustomNodeExecutorRegistry() {
        // When
        environment = HensuFactory.builder().nodeExecutorRegistry(mockNodeExecutorRegistry).build();

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getNodeExecutorRegistry()).isSameAs(mockNodeExecutorRegistry);
    }

    @Test
    void shouldBuildEnvironmentWithCustomAgentRegistry() {
        // When
        environment = HensuFactory.builder().agentRegistry(mockAgentRegistry).build();

        // Then
        assertThat(environment).isNotNull();
        assertThat(environment.getAgentRegistry()).isSameAs(mockAgentRegistry);
    }

    @Test
    void shouldBuildEnvironmentWithCustomConfig() {
        // Given
        HensuConfig config =
                HensuConfig.builder()
                        .useVirtualThreads(false)
                        .threadPoolSize(20)
                        .rubricStorageType("memory")
                        .build();

        // When
        environment = HensuFactory.builder().config(config).build();

        // Then
        assertThat(environment).isNotNull();
    }

    @Test
    void shouldCloseEnvironmentProperly() {
        // Given
        environment = HensuFactory.createEnvironment();

        // When
        environment.close();

        // Then - no exception should be thrown
        assertThat(environment).isNotNull();
    }

    @Test
    void shouldCreateInMemoryRubricRepository() {
        // When
        environment = HensuFactory.createEnvironment();

        // Then
        RubricRepository repository = environment.getRubricRepository();
        assertThat(repository).isNotNull();
    }

    @Test
    void shouldCreateWorkflowExecutor() {
        // When
        environment = HensuFactory.createEnvironment();

        // Then
        WorkflowExecutor executor = environment.getWorkflowExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    void shouldUseVirtualThreadsWhenConfigured() {
        // Given
        HensuConfig config = HensuConfig.builder().useVirtualThreads(true).build();

        // When
        environment = HensuFactory.createEnvironment(config);

        // Then
        assertThat(environment).isNotNull();
        // Virtual threads are used internally - we verify environment is created successfully
    }

    @Test
    void shouldUseFixedThreadPoolWhenVirtualThreadsDisabled() {
        // Given
        HensuConfig config =
                HensuConfig.builder().useVirtualThreads(false).threadPoolSize(4).build();

        // When
        environment = HensuFactory.createEnvironment(config);

        // Then
        assertThat(environment).isNotNull();
        // Fixed thread pool is used internally - we verify environment is created successfully
    }
}
