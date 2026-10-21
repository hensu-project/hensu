package io.hensu.server.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.server.persistence.LeaseLostException;
import io.hensu.server.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckpointingListenerTest {

    private WorkflowStateRepository stateRepository;
    private ExecutionListener observers;
    private CheckpointingListener listener;
    private HensuState state;

    @BeforeEach
    void setUp() {
        stateRepository = mock(WorkflowStateRepository.class);
        observers = mock(ExecutionListener.class);
        listener = new CheckpointingListener(stateRepository, "tenant-1", observers);
        state =
                new HensuState.Builder()
                        .workflowId("wf-1")
                        .executionId("exec-1")
                        .currentNode("node-1")
                        .build();
    }

    /// A lost lease must stop the run. Inside the observer composite it would be
    /// logged and the run would go on calling tools on two nodes at once.
    @Test
    void shouldPropagateLostLeaseToStopTheRun() {
        doThrow(new LeaseLostException("exec-1")).when(stateRepository).save(eq("tenant-1"), any());

        assertThatThrownBy(() -> listener.onCheckpoint(state))
                .isInstanceOf(LeaseLostException.class);
        verify(observers, never()).onCheckpoint(any());
    }

    /// Any other persistence failure keeps its old meaning: logged, run continues.
    @Test
    void shouldContainOtherPersistenceFailures() {
        doThrow(new PersistenceException("database down", null))
                .when(stateRepository)
                .save(eq("tenant-1"), any());

        listener.onCheckpoint(state);

        verify(observers).onCheckpoint(state);
        assertThat(listener.lastCheckpoint()).isSameAs(state);
    }
}
