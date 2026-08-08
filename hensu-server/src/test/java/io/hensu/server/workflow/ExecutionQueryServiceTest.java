package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionQueryServiceTest {

    private WorkflowStateRepository stateRepository;
    private ExecutionQueryService service;

    @BeforeEach
    void setUp() {
        stateRepository = mock(WorkflowStateRepository.class);
        service = new ExecutionQueryService(stateRepository);
    }

    @Test
    void shouldFilterUnderscorePrefixedKeysFromOutput() {
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("_tenant_id", "tenant-1");
        mixed.put("_execution_id", "exec-1");
        mixed.put("_last_output", "internal routing value");
        mixed.put("summary", "Order processed successfully");
        mixed.put("approved", true);
        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-1",
                        "exec-1",
                        null,
                        mixed,
                        Map.of(),
                        null,
                        null,
                        Instant.now(),
                        "completed");
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(snapshot));

        ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

        assertThat(output.output()).doesNotContainKey("_tenant_id");
        assertThat(output.output()).doesNotContainKey("_execution_id");
        assertThat(output.output()).doesNotContainKey("_last_output");
        assertThat(output.output()).containsEntry("summary", "Order processed successfully");
        assertThat(output.output()).containsEntry("approved", true);
    }

    @Test
    void shouldIncludeCorrelationIdWhenPhaseIsAwaiting() {
        ExecutionPhase.Awaiting awaiting =
                new ExecutionPhase.Awaiting(
                        "draft", "ReviewPostProcessor", null, "corr-42", Instant.now());
        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-1",
                        "exec-1",
                        "draft",
                        Map.of(),
                        Map.of(),
                        null,
                        awaiting,
                        Instant.now(),
                        "paused");
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(snapshot));

        ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

        assertThat(status.correlationId()).isEqualTo("corr-42");
        assertThat(status.status()).isEqualTo("PAUSED");
    }

    @Test
    void shouldReturnNullCorrelationIdWhenPhaseIsNotAwaiting() {
        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-1",
                        "exec-1",
                        null,
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        Instant.now(),
                        "completed");
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(snapshot));

        ExecutionStatus status = service.getExecutionStatus("tenant-1", "exec-1");

        assertThat(status.correlationId()).isNull();
        assertThat(status.status()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldIncludeCorrelationIdInPausedExecutionSummary() {
        ExecutionPhase.Awaiting awaiting =
                new ExecutionPhase.Awaiting(
                        "draft", "ReviewPostProcessor", null, "corr-99", Instant.now());
        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-1",
                        "exec-1",
                        "draft",
                        Map.of(),
                        Map.of(),
                        null,
                        awaiting,
                        Instant.now(),
                        "paused");
        when(stateRepository.findPaused("tenant-1")).thenReturn(List.of(snapshot));

        List<ExecutionSummary> paused = service.listPausedExecutions("tenant-1");

        assertThat(paused).hasSize(1);
        assertThat(paused.getFirst().correlationId()).isEqualTo("corr-99");
    }

    @Test
    void shouldReturnEmptyMapWhenAllKeysAreInternal() {
        Map<String, Object> internalOnly = new HashMap<>();
        internalOnly.put("_tenant_id", "tenant-1");
        internalOnly.put("_execution_id", "exec-1");
        HensuSnapshot snapshot =
                new HensuSnapshot(
                        "wf-1",
                        "exec-1",
                        null,
                        internalOnly,
                        Map.of(),
                        null,
                        null,
                        Instant.now(),
                        "completed");
        when(stateRepository.findByExecutionId("tenant-1", "exec-1"))
                .thenReturn(Optional.of(snapshot));

        ExecutionOutput output = service.getExecutionResult("tenant-1", "exec-1");

        assertThat(output.output()).isEmpty();
    }
}
