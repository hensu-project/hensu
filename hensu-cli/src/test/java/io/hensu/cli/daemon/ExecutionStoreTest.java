package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecutionStoreTest {

    @Test
    void evictExpired_removesOnlyTerminalExecutionsPastTtl_leavesOthersIntact() throws Exception {
        var store = new ExecutionStore(Duration.ofMinutes(30));

        // (1) RUNNING — never evicted regardless of age
        var running = new StoredExecution("running", "wf");
        running.markRunning("node-1");
        store.register(running);

        // (2) FAILED 2 hours ago — past TTL, should be evicted
        var oldFailed = new StoredExecution("old-failed", "wf");
        store.register(oldFailed);
        oldFailed.markFailed("boom", "{}");
        setCompletedAt(oldFailed, Instant.now().minus(Duration.ofHours(2)));

        // (3) FAILED 10 minutes ago — within TTL (30min), should NOT be evicted
        var recentFailed = new StoredExecution("recent-failed", "wf");
        store.register(recentFailed);
        recentFailed.markFailed("boom", "{}");
        setCompletedAt(recentFailed, Instant.now().minus(Duration.ofMinutes(10)));

        invokeEvict(store);

        assertThat(store.get("running")).isNotNull();
        assertThat(store.get("old-failed")).isNull();
        assertThat(store.get("recent-failed")).isNotNull();
    }

    @Test
    void register_duplicateId_throwsIllegalArgumentException() {
        var store = new ExecutionStore();
        store.register(new StoredExecution("dup-id", "wf"));

        assertThatThrownBy(() -> store.register(new StoredExecution("dup-id", "wf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup-id");
    }

    // — Helpers ———————————————————————————————————————————————————————————————

    private static void invokeEvict(ExecutionStore store) throws Exception {
        Method m = ExecutionStore.class.getDeclaredMethod("evictExpired");
        m.setAccessible(true);
        m.invoke(store);
    }

    private static void setCompletedAt(StoredExecution exec, Instant instant) throws Exception {
        Field f = StoredExecution.class.getDeclaredField("completedAt");
        f.setAccessible(true);
        f.set(exec, instant);
    }
}
