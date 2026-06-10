package io.hensu.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.server.streaming.ExecutionEvent;
import io.hensu.server.streaming.ExecutionEventBroadcaster;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class ExecutionResultHandlerTest {

    private WorkflowStateRepository stateRepository;
    private ExecutionEventBroadcaster eventBroadcaster;
    private static final Logger LOG = Logger.getLogger(ExecutionResultHandlerTest.class);

    @BeforeEach
    void setUp() {
        stateRepository = mock(WorkflowStateRepository.class);
        eventBroadcaster = mock(ExecutionEventBroadcaster.class);
    }

    private static HensuState stateAt(String node) {
        return new HensuState.Builder()
                .workflowId("wf-1")
                .executionId("exec-1")
                .currentNode(node)
                .context(new HashMap<>(Map.of("result", "data")))
                .build();
    }

    private static HensuState pausedStateWithAwaiting() {
        HensuState state = stateAt("review-node");
        state.setPhase(
                new ExecutionPhase.Awaiting(
                        "review-node", "reviewer", null, "corr-123", Instant.now()));
        return state;
    }

    static Stream<Arguments> resultVariants() {
        return Stream.of(
                Arguments.of(
                        "Completed",
                        new ExecutionResult.Completed(stateAt("end"), ExitStatus.SUCCESS),
                        "completed",
                        ExecutionEvent.ExecutionCompleted.class),
                Arguments.of(
                        "Rejected",
                        new ExecutionResult.Rejected("bad output", stateAt("review")),
                        "rejected",
                        ExecutionEvent.ExecutionCompleted.class),
                Arguments.of(
                        "Paused",
                        new ExecutionResult.Paused(pausedStateWithAwaiting()),
                        "paused",
                        ExecutionEvent.ExecutionPaused.class),
                Arguments.of(
                        "Failure",
                        new ExecutionResult.Failure(
                                stateAt("broken"), new IllegalStateException("boom")),
                        "failed",
                        ExecutionEvent.ExecutionError.class),
                Arguments.of(
                        "Success",
                        new ExecutionResult.Success(stateAt("mid")),
                        "failed",
                        ExecutionEvent.ExecutionError.class));
    }

    @ParameterizedTest(name = "{0} → saved as \"{2}\"")
    @MethodSource("resultVariants")
    void shouldSaveSnapshotAndPublishEvent(
            String label,
            ExecutionResult result,
            String expectedStatus,
            Class<? extends ExecutionEvent> expectedEventType) {

        ExecutionResultHandler.handle(
                result,
                "tenant-1",
                "exec-1",
                "wf-1",
                stateRepository,
                eventBroadcaster,
                LOG,
                "execute");

        ArgumentCaptor<HensuSnapshot> snapshotCaptor = ArgumentCaptor.forClass(HensuSnapshot.class);
        verify(stateRepository).save(eq("tenant-1"), snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().checkpointReason()).isEqualTo(expectedStatus);

        verify(eventBroadcaster).publish(eq("exec-1"), any(expectedEventType));
    }
}
