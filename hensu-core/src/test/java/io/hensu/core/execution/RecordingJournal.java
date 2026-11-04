package io.hensu.core.execution;

import io.hensu.core.state.HensuSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/// An in-memory journal that keeps what it records, so a test can run a node attempt,
/// then run it again as a recovery would, against the same journal.
public final class RecordingJournal implements ExecutionJournal {

    public final Map<String, String> turns = new ConcurrentHashMap<>();
    public final Map<String, HensuSnapshot> flows = new ConcurrentHashMap<>();
    public final AtomicInteger liveTurns = new AtomicInteger();

    @Override
    public String turn(String position, int index, Supplier<String> live) {
        return turns.computeIfAbsent(
                position + "/" + index,
                _ -> {
                    String turn = live.get();
                    liveTurns.incrementAndGet();
                    return turn;
                });
    }

    @Override
    public Optional<HensuSnapshot> flowCheckpoint(String scope) {
        return Optional.ofNullable(flows.get(scope));
    }

    @Override
    public void recordFlowCheckpoint(String scope, HensuSnapshot snapshot) {
        flows.put(scope, snapshot);
    }
}
