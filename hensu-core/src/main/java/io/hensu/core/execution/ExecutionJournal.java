package io.hensu.core.execution;

import io.hensu.core.state.HensuSnapshot;
import java.util.Optional;
import java.util.function.Supplier;

/// Durable record of the steps a run cannot reproduce, so that a recovered run makes
/// exactly the decisions the original made.
///
/// A main-flow checkpoint fixes everything up to the start of a node attempt. Within
/// the attempt, two things are not reproducible: what the model answers, and what a
/// tool returns. The journal records the first here, as *turns* keyed by the attempt's
/// position ({@link io.hensu.core.state.HensuState#position()}); tool outcomes are
/// recorded by the runtime's tool seam under the call's key
/// ({@link io.hensu.core.tool.ToolCall#key()}). Nested flows – fork branches,
/// sub-workflows – have no execution row of their own, so their checkpoints are
/// recorded here as well, under the flow's scope.
///
/// Every record is written before the run acts on it. An implementation backed by a
/// shared store fences each write on the run's claim and throws
/// {@link RunPreemptedException} when the claim no longer stands, which is what keeps
/// a superseded run from recording a decision its successor would then replay.
///
/// @implNote Implementations must be safe to call from the concurrent branches of a
///     fork or parallel node; branches never share a position or a scope.
/// @see RunPreemptedException
public interface ExecutionJournal {

    /// A journal that records nothing and replays nothing, for runtimes that do not
    /// recover executions (the CLI) and for tests.
    ExecutionJournal NONE =
            new ExecutionJournal() {
                @Override
                public String turn(String position, int index, Supplier<String> live) {
                    return live.get();
                }

                @Override
                public Optional<HensuSnapshot> flowCheckpoint(String scope) {
                    return Optional.empty();
                }

                @Override
                public void recordFlowCheckpoint(String scope, HensuSnapshot snapshot) {}
            };

    /// Returns the turn recorded at `index` of the attempt at `position`, or computes it
    /// with `live`, records it durably and returns it.
    ///
    /// @param position the node attempt, not null
    /// @param index the turn's index within the attempt, zero-based
    /// @param live produces the turn when none is recorded, not null
    /// @return the recorded or freshly recorded turn, never null
    /// @throws RunPreemptedException if the turn cannot be recorded under the run's claim
    String turn(String position, int index, Supplier<String> live);

    /// Returns the latest checkpoint a nested flow recorded under `scope`.
    ///
    /// @param scope the nested flow, not null
    /// @return the checkpoint, or empty when the flow never recorded one
    Optional<HensuSnapshot> flowCheckpoint(String scope);

    /// Records a nested flow's checkpoint, replacing the previous one for its scope.
    ///
    /// @param scope the nested flow, not null
    /// @param snapshot the flow's state at the start of its next node attempt, not null
    /// @throws RunPreemptedException if the checkpoint cannot be recorded under the run's claim
    void recordFlowCheckpoint(String scope, HensuSnapshot snapshot);
}
