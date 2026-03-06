package io.hensu.cli.daemon;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/// In-memory registry for all daemon-managed workflow executions.
///
/// Maintains the full lifecycle of each {@link StoredExecution} from submission
/// through terminal state, and periodically evicts completed executions after the
/// configured TTL.
///
/// ### Contracts
/// - **Precondition**: {@code register()} must be called before {@code get()} for the same id
/// - **Postcondition**: after {@code start()}, completed executions are evicted after TTL
/// - **Invariant**: {@code get(id)} returns the same {@link StoredExecution} instance for
///   the lifetime of the execution
///
/// @implNote **Thread-safe**. Uses {@link ConcurrentHashMap} for the execution map.
/// State transitions on individual {@link StoredExecution} are internally synchronized.
///
/// @apiNote Call {@link #start()} once at daemon startup to activate TTL eviction.
/// Call {@link #stop()} on daemon shutdown to release the eviction scheduler.
///
/// @see StoredExecution
/// @see DaemonServer
public final class ExecutionStore {

    private static final Logger log = Logger.getLogger(ExecutionStore.class.getName());

    /// Default TTL for completed executions before eviction.
    public static final Duration DEFAULT_RESULT_TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<String, StoredExecution> executions = new ConcurrentHashMap<>();
    private final Duration resultTtl;
    private ScheduledExecutorService evictionScheduler;

    /// Creates a store with the {@link #DEFAULT_RESULT_TTL}.
    public ExecutionStore() {
        this(DEFAULT_RESULT_TTL);
    }

    /// Creates a store with a custom TTL for completed execution records.
    ///
    /// @param resultTtl how long to retain terminal executions before eviction, not null
    public ExecutionStore(Duration resultTtl) {
        this.resultTtl = resultTtl;
    }

    /// Starts the periodic eviction scheduler.
    ///
    /// @apiNote **Side effects**: starts a background daemon thread for TTL eviction.
    /// Must be called once at daemon startup.
    public void start() {
        evictionScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "hensu-eviction");
                            t.setDaemon(true);
                            return t;
                        });
        evictionScheduler.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
    }

    /// Stops the eviction scheduler.
    ///
    /// @apiNote **Side effects**: shuts down the background eviction thread.
    public void stop() {
        if (evictionScheduler != null) {
            evictionScheduler.shutdown();
        }
    }

    /// Registers a new execution in {@link ExecutionStatus#QUEUED} state.
    ///
    /// @param execution the execution to register, not null
    /// @throws IllegalArgumentException if an execution with the same id already exists
    public void register(StoredExecution execution) {
        if (executions.putIfAbsent(execution.getId(), execution) != null) {
            throw new IllegalArgumentException(
                    "Execution already registered: " + execution.getId());
        }
    }

    /// Returns the execution with the given id, if present.
    ///
    /// @param id execution identifier, not null
    /// @return the stored execution, may be null if not found
    public StoredExecution get(String id) {
        return executions.get(id);
    }

    /// Returns all currently tracked executions.
    ///
    /// @return snapshot of all executions, never null
    public Collection<StoredExecution> all() {
        return new ArrayList<>(executions.values());
    }

    /// Returns all executions currently in {@link ExecutionStatus#RUNNING} state.
    ///
    /// @return list of running executions, never null, may be empty
    public List<StoredExecution> running() {
        return executions.values().stream()
                .filter(e -> e.getStatus() == ExecutionStatus.RUNNING)
                .toList();
    }

    // — Internal ——————————————————————————————————————————————————————————————

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(resultTtl);
        executions
                .entrySet()
                .removeIf(
                        entry -> {
                            StoredExecution exec = entry.getValue();
                            if (exec.getStatus().isTerminal()
                                    && exec.getCompletedAt() != null
                                    && exec.getCompletedAt().isBefore(cutoff)) {
                                log.fine("Evicting expired execution: " + exec.getId());
                                return true;
                            }
                            return false;
                        });
    }
}
