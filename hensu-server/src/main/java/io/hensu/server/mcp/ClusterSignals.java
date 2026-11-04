package io.hensu.server.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Wake-ups for the threads on this node that wait on the store.
///
/// A signal says only "look again": every waiter re-reads the store after waking, and
/// every wait is bounded, so a signal that is lost – a notification that arrived while
/// the listening connection was down – costs latency and never correctness. Waiters
/// register before they read, so a signal that lands between the read and the wait is
/// not missed.
///
/// Signals reach this node from PostgreSQL `NOTIFY` in a clustered deployment and
/// directly from the in-memory stores in a single-node one.
///
/// @implNote Thread-safe. One generation per key: a signal wakes every waiter registered
///     before it, and later waiters start a new generation.
@ApplicationScoped
public class ClusterSignals {

    private final Map<Long, CompletableFuture<Void>> outcomes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> intents = new ConcurrentHashMap<>();
    private final List<Runnable> runnableListeners = new CopyOnWriteArrayList<>();

    /// A registration to wait on.
    public static final class Waiter {
        private final CompletableFuture<Void> signal;

        private Waiter(CompletableFuture<Void> signal) {
            this.signal = signal;
        }

        /// Waits until signalled or `max` elapses.
        ///
        /// @param max the longest wait, not null
        /// @throws InterruptedException if the waiting thread is interrupted
        public void await(Duration max) throws InterruptedException {
            try {
                signal.get(Math.max(1, max.toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException ignored) {
                // the caller re-reads the store either way
            }
        }
    }

    /// Registers for the settlement of an intent.
    public Waiter outcomeWaiter(long intentId) {
        return new Waiter(outcomes.computeIfAbsent(intentId, _ -> new CompletableFuture<>()));
    }

    /// Registers for new intents of a tenant.
    public Waiter intentWaiter(String tenantId) {
        return new Waiter(intents.computeIfAbsent(tenantId, _ -> new CompletableFuture<>()));
    }

    /// An intent settled.
    public void outcome(long intentId) {
        CompletableFuture<Void> signal = outcomes.remove(intentId);
        if (signal != null) {
            signal.complete(null);
        }
    }

    /// A tenant has an intent waiting to be offered.
    public void intent(String tenantId) {
        CompletableFuture<Void> signal = intents.remove(tenantId);
        if (signal != null) {
            signal.complete(null);
        }
    }

    /// An execution was released and can be picked up.
    public void runnable() {
        runnableListeners.forEach(Runnable::run);
    }

    /// Registers what to do when an execution is released.
    public void onRunnable(Runnable listener) {
        runnableListeners.add(listener);
    }

    /// Wakes every waiter: signals may have been lost, so everyone re-reads.
    public void resync() {
        outcomes.keySet().forEach(this::outcome);
        intents.keySet().forEach(this::intent);
        runnable();
    }
}
