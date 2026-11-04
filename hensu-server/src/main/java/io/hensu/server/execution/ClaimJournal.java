package io.hensu.server.execution;

import io.hensu.core.execution.ExecutionJournal;
import io.hensu.core.state.HensuSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/// The engine's journal for one run, recorded in the {@link ExecutionStore} under the
/// run's claim.
///
/// A turn already journaled – by this run or by the one it recovered from – is handed
/// back without producing it again; a new one is produced, journaled under the claim,
/// and only then returned, so the session never acts on a turn its successor would not
/// find.
public final class ClaimJournal implements ExecutionJournal {

    private final ExecutionStore store;
    private final RunHandle run;

    /// @param store where the journal is kept, not null
    /// @param run the run whose claim fences every record, not null
    public ClaimJournal(ExecutionStore store, RunHandle run) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
    }

    @Override
    public String turn(String position, int index, Supplier<String> live) {
        run.checkLive();
        Optional<String> recorded = store.turn(run.claim(), position, index);
        if (recorded.isPresent()) {
            return recorded.get();
        }
        String produced = live.get();
        run.checkLive();
        return store.recordTurn(run.claim(), position, index, produced);
    }

    @Override
    public Optional<HensuSnapshot> flowCheckpoint(String scope) {
        return store.flowCheckpoint(run.claim(), scope);
    }

    @Override
    public void recordFlowCheckpoint(String scope, HensuSnapshot snapshot) {
        run.checkLive();
        store.recordFlowCheckpoint(run.claim(), scope, snapshot);
    }
}
