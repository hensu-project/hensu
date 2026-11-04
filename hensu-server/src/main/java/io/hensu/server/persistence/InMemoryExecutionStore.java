package io.hensu.server.persistence;

import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.WorkflowStateRepository;
import io.hensu.server.execution.Claim;
import io.hensu.server.execution.ExecutionStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Single-process {@link ExecutionStore} for deployments without a database (the `inmem`
/// profile).
///
/// The same state machine and the same fencing as the PostgreSQL store, under one lock.
/// Snapshots are kept in a core in-memory repository, and the store's reads answer from it
/// with the same filters as the database, so a paused execution is listed and an
/// execution's state is visible exactly as with PostgreSQL.
/// There is only one node, so nothing expires: an execution is picked up only after it is
/// released.
///
/// @implNote Thread-safe: every operation holds the store's lock.
public final class InMemoryExecutionStore implements ExecutionStore {

    /// Told when an execution pauses or finishes, so its tool intents follow it as they do
    /// in the PostgreSQL store's settle statement.
    ///
    /// Called after the store's lock is released, so a resume may already have claimed the
    /// execution again; the listener touches only intents made under the settled claim.
    @FunctionalInterface
    public interface SettleListener {

        /// @param claim the claim the execution settled under, not null
        /// @param finished true when it finished, false when it paused
        void settled(Claim claim, boolean finished);
    }

    private enum RunState {
        RUNNING,
        RUNNABLE,
        PAUSED,
        DONE
    }

    private record Key(String tenantId, String executionId) {}

    private static final class Row {
        RunState state;
        long epoch;

        Row(RunState state, long epoch) {
            this.state = state;
            this.epoch = epoch;
        }
    }

    private final WorkflowStateRepository snapshots;
    private final SettleListener onSettled;
    private final Map<Key, Row> rows = new HashMap<>();
    private final Map<Key, Map<String, String>> turns = new HashMap<>();
    private final Map<Key, Map<String, HensuSnapshot>> flows = new HashMap<>();
    /// Start keys, by tenant and key, with the execution and request digest they belong to.
    private final Map<Key, StartKeyOwner> startKeys = new HashMap<>();

    private record StartKeyOwner(String executionId, String requestDigest) {}

    /// @param snapshots where the store keeps snapshots, not null
    /// @param onSettled told when an execution pauses or finishes, not null
    public InMemoryExecutionStore(WorkflowStateRepository snapshots, SettleListener onSettled) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.onSettled = Objects.requireNonNull(onSettled, "onSettled must not be null");
    }

    @Override
    public synchronized StartOutcome start(
            String tenantId, HensuSnapshot initial, StartKey startKey) {
        if (startKey != null) {
            StartKeyOwner owner = startKeys.get(new Key(tenantId, startKey.key()));
            if (owner != null) {
                return new StartOutcome.Existing(
                        owner.executionId(),
                        owner.requestDigest().equals(startKey.requestDigest()));
            }
            startKeys.put(
                    new Key(tenantId, startKey.key()),
                    new StartKeyOwner(initial.executionId(), startKey.requestDigest()));
        }
        Key key = new Key(tenantId, initial.executionId());
        rows.put(key, new Row(RunState.RUNNING, 1));
        snapshots.save(tenantId, initial);
        return new StartOutcome.Started(new Claim(tenantId, initial.executionId(), 1));
    }

    @Override
    public synchronized Optional<Claim> resume(String tenantId, String executionId) {
        Row row = rows.get(new Key(tenantId, executionId));
        if (row == null || row.state != RunState.PAUSED) {
            return Optional.empty();
        }
        row.state = RunState.RUNNING;
        row.epoch++;
        return Optional.of(new Claim(tenantId, executionId, row.epoch));
    }

    @Override
    public synchronized List<Claim> pickUp(int limit) {
        List<Claim> taken = new ArrayList<>();
        for (Map.Entry<Key, Row> entry : rows.entrySet()) {
            if (taken.size() >= limit) {
                break;
            }
            Row row = entry.getValue();
            if (row.state == RunState.RUNNABLE) {
                row.state = RunState.RUNNING;
                row.epoch++;
                taken.add(
                        new Claim(
                                entry.getKey().tenantId(),
                                entry.getKey().executionId(),
                                row.epoch));
            }
        }
        return taken;
    }

    @Override
    public synchronized Set<Claim> renew(Collection<Claim> claims) {
        Set<Claim> renewed = new HashSet<>();
        for (Claim claim : claims) {
            if (holds(claim)) {
                renewed.add(claim);
            }
        }
        return renewed;
    }

    @Override
    public synchronized void checkpoint(Claim claim, HensuSnapshot snapshot) {
        require(claim);
        snapshots.save(claim.tenantId(), snapshot);
        Key key = key(claim);
        prune(turns.get(key), snapshot.step());
        prune(flows.get(key), snapshot.step());
    }

    // The listener is told after the lock is released: the intents ask this store whether a
    // claim stands while holding their own lock, so calling them under this one could deadlock.
    @Override
    public void pause(Claim claim, HensuSnapshot snapshot) {
        settle(claim, snapshot, RunState.PAUSED);
        onSettled.settled(claim, false);
    }

    @Override
    public void finish(Claim claim, HensuSnapshot snapshot) {
        settle(claim, snapshot, RunState.DONE);
        onSettled.settled(claim, true);
    }

    private synchronized void settle(Claim claim, HensuSnapshot snapshot, RunState state) {
        require(claim);
        rows.get(key(claim)).state = state;
        snapshots.save(claim.tenantId(), snapshot);
        turns.remove(key(claim));
        flows.remove(key(claim));
    }

    @Override
    public synchronized void release(Claim claim) {
        require(claim);
        rows.get(key(claim)).state = RunState.RUNNABLE;
    }

    @Override
    public synchronized Optional<HensuSnapshot> findByExecutionId(
            String tenantId, String executionId) {
        return snapshots.findByExecutionId(tenantId, executionId);
    }

    // Mirrors the SQL: awaiting a decision (the repository's filter) and no run holds it.
    @Override
    public synchronized List<HensuSnapshot> findPaused(String tenantId) {
        return snapshots.findPaused(tenantId).stream()
                .filter(s -> stateOf(tenantId, s.executionId()) == RunState.PAUSED)
                .toList();
    }

    @Override
    public synchronized List<HensuSnapshot> findByWorkflowId(String tenantId, String workflowId) {
        return snapshots.findByWorkflowId(tenantId, workflowId);
    }

    @Override
    public synchronized Optional<String> turn(Claim claim, String position, int index) {
        return Optional.ofNullable(
                turns.getOrDefault(key(claim), Map.of()).get(position + "#" + index));
    }

    @Override
    public synchronized String recordTurn(Claim claim, String position, int index, String payload) {
        String existing = turns.getOrDefault(key(claim), Map.of()).get(position + "#" + index);
        if (existing != null) {
            return existing;
        }
        require(claim);
        turns.computeIfAbsent(key(claim), _ -> new HashMap<>())
                .put(position + "#" + index, payload);
        return payload;
    }

    @Override
    public synchronized Optional<HensuSnapshot> flowCheckpoint(Claim claim, String scope) {
        return Optional.ofNullable(flows.getOrDefault(key(claim), Map.of()).get(scope));
    }

    @Override
    public synchronized void recordFlowCheckpoint(
            Claim claim, String scope, HensuSnapshot snapshot) {
        require(claim);
        flows.computeIfAbsent(key(claim), _ -> new HashMap<>()).put(scope, snapshot);
    }

    private static <V> void prune(Map<String, V> records, long step) {
        if (records != null) {
            records.keySet().removeIf(position -> ExecutionStore.mainStep(position) < step);
        }
    }

    /// Answers whether `claim` is the execution's current, running claim.
    ///
    /// @param claim the claim, not null
    /// @return whether writes under it would commit
    public synchronized boolean holdsClaim(Claim claim) {
        return holds(claim);
    }

    private RunState stateOf(String tenantId, String executionId) {
        Row row = rows.get(new Key(tenantId, executionId));
        return row != null ? row.state : null;
    }

    private boolean holds(Claim claim) {
        Row row = rows.get(key(claim));
        return row != null && row.state == RunState.RUNNING && row.epoch == claim.epoch();
    }

    private void require(Claim claim) {
        if (!holds(claim)) {
            throw new RunPreemptedException("Claim " + claim + " no longer stands");
        }
    }

    private static Key key(Claim claim) {
        return new Key(claim.tenantId(), claim.executionId());
    }
}
