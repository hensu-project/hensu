package io.hensu.server.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.state.InMemoryWorkflowStateRepository;
import io.hensu.server.persistence.InMemoryExecutionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The checkpoint that opens a node attempt is the run's last chance to find out its claim
/// is gone before the attempt calls tools (S4). These tests pin that a refused or failed
/// checkpoint stops the run instead of being logged and passed over.
class CheckpointingListenerTest {

    private InMemoryWorkflowStateRepository snapshots;
    private InMemoryExecutionStore store;
    private List<HensuState> observed;

    @BeforeEach
    void setUp() {
        snapshots = new InMemoryWorkflowStateRepository();
        store = new InMemoryExecutionStore(snapshots, (_, _) -> {});
        observed = new ArrayList<>();
    }

    @Test
    void writesTheCheckpointUnderTheLiveClaimThenTellsObservers() {
        Claim claim = store.start("t1", HensuSnapshot.from(state(0), "checkpoint"));
        CheckpointingListener listener = listener(new RunHandle(claim));

        HensuState next = state(1);
        listener.onCheckpoint(next);

        assertThat(snapshots.findByExecutionId("t1", "exec-1"))
                .get()
                .extracting(HensuSnapshot::step)
                .isEqualTo(1L);
        assertThat(observed).containsExactly(next);
        assertThat(listener.lastCheckpoint()).isSameAs(next);
    }

    @Test
    void stopsTheRunWhenItsClaimWasSupersededAndTellsNoObserver() {
        Claim first = store.start("t1", HensuSnapshot.from(state(0), "checkpoint"));
        store.release(first);
        Claim second = store.pickUp(1).getFirst();
        assertThat(second.epoch()).isEqualTo(2);

        CheckpointingListener stale = listener(new RunHandle(first));

        assertThatThrownBy(() -> stale.onCheckpoint(state(5)))
                .isInstanceOf(RunPreemptedException.class);
        assertThat(observed).isEmpty();
        assertThat(snapshots.findByExecutionId("t1", "exec-1"))
                .get()
                .extracting(HensuSnapshot::step)
                .isEqualTo(0L);
    }

    @Test
    void stopsBeforeWritingWhenTheRunIsKnownToBePreempted() {
        Claim claim = store.start("t1", HensuSnapshot.from(state(0), "checkpoint"));
        RunHandle run = new RunHandle(claim);
        run.preempt("claim superseded");

        assertThatThrownBy(() -> listener(run).onCheckpoint(state(1)))
                .isInstanceOf(RunPreemptedException.class);
        assertThat(snapshots.findByExecutionId("t1", "exec-1"))
                .get()
                .extracting(HensuSnapshot::step)
                .isEqualTo(0L);
    }

    private CheckpointingListener listener(RunHandle run) {
        return new CheckpointingListener(
                store,
                run,
                new ExecutionListener() {
                    @Override
                    public void onCheckpoint(HensuState state) {
                        observed.add(state);
                    }
                });
    }

    private static HensuState state(long step) {
        return new HensuState.Builder()
                .executionId("exec-1")
                .workflowId("wf-1")
                .currentNode("node")
                .context(new HashMap<>())
                .history(new ExecutionHistory())
                .step(step)
                .build();
    }
}
