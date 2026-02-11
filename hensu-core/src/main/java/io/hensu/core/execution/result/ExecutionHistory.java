package io.hensu.core.execution.result;

import io.hensu.core.rubric.evaluator.RubricEvaluation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// Tracks the complete execution history of a workflow run.
///
/// Records every executed step and backtrack event, enabling:
/// - Time-travel debugging
/// - Execution path analysis
/// - Audit logging
/// - State restoration for backtracking
///
/// @implNote **Not thread-safe**. Should only be accessed from a single
/// workflow execution thread. The `copy()` method creates immutable snapshots
/// for safe sharing.
///
/// @see ExecutionStep for individual step records
/// @see BacktrackEvent for backtrack records
public class ExecutionHistory {

    private List<ExecutionStep> steps = new ArrayList<>();
    private List<BacktrackEvent> backtracks = new ArrayList<>();

    /// Adds an execution step to the history.
    ///
    /// @apiNote **Side effects**: Modifies internal step list
    ///
    /// @param step the step to record, not null
    /// @return a copy of this history for chaining, never null
    public ExecutionHistory addStep(ExecutionStep step) {
        steps.add(step);
        return copy();
    }

    /// Adds a backtrack event to the history.
    ///
    /// @apiNote **Side effects**: Modifies internal backtrack list
    ///
    /// @param backtrack the backtrack event to record, not null
    /// @return a copy of this history for chaining, never null
    public ExecutionHistory addBacktrack(BacktrackEvent backtrack) {
        backtracks.add(backtrack);
        return copy();
    }

    /// Records an automatic backtrack triggered by rubric evaluation.
    ///
    /// @apiNote **Side effects**: Modifies internal backtrack list
    ///
    /// @param from source node ID where backtrack originated, not null
    /// @param to target node ID to backtrack to, not null
    /// @param reason explanation for the backtrack, may be null
    /// @param rubricEvaluation the evaluation that triggered backtracking, not null
    /// @return a copy of this history for chaining, never null
    public ExecutionHistory addAutoBacktrack(
            String from, String to, String reason, RubricEvaluation rubricEvaluation) {
        backtracks.add(
                BacktrackEvent.builder()
                        .from(from)
                        .to(to)
                        .reason(reason)
                        .type(BacktrackType.AUTOMATIC)
                        .rubricScore(rubricEvaluation.getScore())
                        .timestamp(Instant.now())
                        .build());
        return copy();
    }

    /// Records an explicit jump transition (non-failure backtrack).
    ///
    /// @apiNote **Side effects**: Modifies internal backtrack list
    ///
    /// @param from source node ID, not null
    /// @param to target node ID, not null
    /// @param reason explanation for the jump, may be null
    /// @param rubricEvaluation associated evaluation, not null
    /// @return a copy of this history for chaining, never null
    public ExecutionHistory addJump(
            String from, String to, String reason, RubricEvaluation rubricEvaluation) {
        backtracks.add(
                BacktrackEvent.builder()
                        .from(from)
                        .to(to)
                        .reason(reason)
                        .type(BacktrackType.JUMP)
                        .rubricScore(rubricEvaluation.getScore())
                        .timestamp(Instant.now())
                        .build());
        return copy();
    }

    /// Returns all recorded execution steps.
    ///
    /// @return immutable copy of the steps list, never null
    public List<ExecutionStep> getSteps() {
        return List.copyOf(steps);
    }

    /// Returns all recorded backtrack events.
    ///
    /// @return immutable copy of the backtracks list, never null
    public List<BacktrackEvent> getBacktracks() {
        return List.copyOf(backtracks);
    }

    /// Creates a mutable copy of this history.
    ///
    /// @return a new ExecutionHistory with copied data, never null
    public ExecutionHistory copy() {
        ExecutionHistory history = new ExecutionHistory();
        history.steps = new ArrayList<>(steps);
        history.backtracks = new ArrayList<>(backtracks);
        return history;
    }
}
