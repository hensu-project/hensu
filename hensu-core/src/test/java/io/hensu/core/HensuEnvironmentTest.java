package io.hensu.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.executor.NodeExecutorRegistry;
import io.hensu.core.rubric.repository.RubricRepository;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HensuEnvironmentTest {

    @Mock private WorkflowExecutor workflowExecutor;

    @Mock private NodeExecutorRegistry nodeExecutorRegistry;

    @Mock private AgentRegistry agentRegistry;

    @Mock private RubricRepository rubricRepository;

    @Mock private ExecutorService executorService;

    private HensuEnvironment environment;

    @BeforeEach
    void setUp() {
        environment =
                new HensuEnvironment(
                        workflowExecutor,
                        nodeExecutorRegistry,
                        agentRegistry,
                        rubricRepository,
                        executorService,
                        null);
    }

    @Test
    void shouldReturnWorkflowExecutor() {
        // When
        WorkflowExecutor result = environment.getWorkflowExecutor();

        // Then
        assertThat(result).isSameAs(workflowExecutor);
    }

    @Test
    void shouldReturnAgentRegistry() {
        // When
        AgentRegistry result = environment.getAgentRegistry();

        // Then
        assertThat(result).isSameAs(agentRegistry);
    }

    @Test
    void shouldReturnRubricRepository() {
        // When
        RubricRepository result = environment.getRubricRepository();

        // Then
        assertThat(result).isSameAs(rubricRepository);
    }

    @Test
    void shouldShutdownExecutorServiceOnClose() {
        // When
        environment.close();

        // Then
        verify(executorService).shutdown();
    }

    @Test
    void shouldImplementAutoCloseable() {
        // Then
        assertThat(environment).isInstanceOf(AutoCloseable.class);
    }

    @Test
    void shouldAllowTryWithResources() {
        // Given/When
        try (HensuEnvironment env =
                new HensuEnvironment(
                        workflowExecutor,
                        nodeExecutorRegistry,
                        agentRegistry,
                        rubricRepository,
                        executorService,
                        null)) {
            assertThat(env.getWorkflowExecutor()).isNotNull();
        }

        // Then
        verify(executorService).shutdown();
    }
}
