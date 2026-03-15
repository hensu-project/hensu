package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.Test;

class StoredExecutionTest {

    // — Terminal transition broadcasts ———————————————————————————————————————

    @Test
    void markCompleted_broadcastsFinalFrameThenPoison_toAllSubscribers() {
        var exec = new StoredExecution("e1", "wf");
        BlockingQueue<String> q1 = new ArrayBlockingQueue<>(10);
        BlockingQueue<String> q2 = new ArrayBlockingQueue<>(10);
        exec.addSubscriber(q1);
        exec.addSubscriber(q2);

        exec.markCompleted(completedResult(), "{\"t\":\"exec_end\"}");

        // Each subscriber must receive the final frame followed by the poison pill
        assertThat(q1.poll()).isEqualTo("{\"t\":\"exec_end\"}");
        assertThat(q1.poll()).isEqualTo(StoredExecution.poisonPill());
        assertThat(q2.poll()).isEqualTo("{\"t\":\"exec_end\"}");
        assertThat(q2.poll()).isEqualTo(StoredExecution.poisonPill());
    }

    @Test
    void markCompleted_subscriberQueueAcceptsFinalFrameButRejectsPoisonPill_evictsSubscriber() {
        // Capacity=1: final frame accepted, poison pill rejected → subscriber evicted
        var exec = new StoredExecution("e2", "wf");
        BlockingQueue<String> tight = new ArrayBlockingQueue<>(1);
        exec.addSubscriber(tight);

        exec.markCompleted(completedResult(), "final-frame");

        assertThat(tight.poll()).isEqualTo("final-frame");
        assertThat(tight).isEmpty(); // poison pill never made it in

        // Eviction confirmed: a fresh subscriber added post-terminal receives broadcasts,
        // but the evicted tight queue does not
        BlockingQueue<String> fresh = new ArrayBlockingQueue<>(10);
        exec.addSubscriber(fresh);
        exec.broadcast("post-terminal");
        assertThat(fresh.poll()).isEqualTo("post-terminal");
        assertThat(tight).isEmpty();
    }

    // — Live broadcast ————————————————————————————————————————————————————————

    @Test
    void broadcast_withFullSubscriberQueue_silentlyEvictsSubscriber() {
        var exec = new StoredExecution("e3", "wf");
        BlockingQueue<String> full = new ArrayBlockingQueue<>(1);
        full.offer("pre-existing"); // fill the queue
        exec.addSubscriber(full);

        exec.broadcast("should-be-dropped");

        // offer returned false → subscriber evicted; subsequent broadcasts don't reach it
        BlockingQueue<String> fresh = new ArrayBlockingQueue<>(10);
        exec.addSubscriber(fresh);
        exec.broadcast("after-eviction");

        assertThat(fresh.poll()).isEqualTo("after-eviction");
        assertThat(full).containsExactly("pre-existing"); // unchanged — evicted, not modified
    }

    // — AWAITING_REVIEW lifecycle ———————————————————————————————————————————

    @Test
    void reviewLifecycle_running_toAwaitingReview_toResumed() {
        var exec = new StoredExecution("e4", "wf");
        exec.markRunning("node-1");

        exec.markAwaitingReview("node-1");
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.AWAITING_REVIEW);
        assertThat(exec.getCurrentNode()).isEqualTo("node-1");
        assertThat(ExecutionStatus.AWAITING_REVIEW.isTerminal()).isFalse();

        exec.markResumedAfterReview("node-2");
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(exec.getCurrentNode()).isEqualTo("node-2");
    }

    // — AWAITING_REVIEW guard conditions ——————————————————————————————————————

    @Test
    void markAwaitingReview_ignoredFromTerminalState() {
        var exec = new StoredExecution("e5", "wf");
        exec.markRunning("node-1");
        exec.markCancelled("{\"t\":\"exec_end\"}");

        exec.markAwaitingReview("node-1");

        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void markResumedAfterReview_ignoredWhenNotAwaitingReview() {
        var exec = new StoredExecution("e6", "wf");
        exec.markRunning("node-1");

        exec.markResumedAfterReview("node-2");

        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(exec.getCurrentNode()).isEqualTo("node-1");
    }

    @Test
    void terminalTransition_fromAwaitingReview() {
        var exec = new StoredExecution("e7", "wf");
        exec.markRunning("node-1");
        exec.markAwaitingReview("node-1");

        exec.markCancelled("{\"t\":\"exec_end\"}");

        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(exec.getCompletedAt()).isNotNull();
    }

    // — updateStatus routing ——————————————————————————————————————————————————

    @Test
    void updateStatus_routesAwaitingReviewAndRunning() {
        var exec = new StoredExecution("e8", "wf");
        exec.markRunning("node-1");
        exec.setCurrentNode("node-2");

        exec.updateStatus(ExecutionStatus.AWAITING_REVIEW);
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.AWAITING_REVIEW);

        exec.updateStatus(ExecutionStatus.RUNNING);
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void updateStatus_ignoresTerminalStatuses() {
        var exec = new StoredExecution("e9", "wf");
        exec.markRunning("node-1");

        exec.updateStatus(ExecutionStatus.COMPLETED);

        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    // — Helpers ———————————————————————————————————————————————————————————————

    private static ExecutionResult.Completed completedResult() {
        return new ExecutionResult.Completed(
                new HensuState(new HashMap<>(), "wf", "end", new ExecutionHistory()),
                ExitStatus.SUCCESS);
    }
}
