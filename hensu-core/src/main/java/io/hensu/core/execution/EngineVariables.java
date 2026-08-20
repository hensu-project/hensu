package io.hensu.core.execution;

import java.util.List;
import java.util.Set;

/// Single source of truth for engine-managed output variable names.
///
/// Engine variables are injected into agent prompts and extracted from agent
/// responses automatically by the execution pipeline. They drive consensus
/// evaluation (score, approval) and downstream routing (recommendation).
///
/// Developers do not declare these in {@code writes()} or {@code yields()} –
/// the engine infers them from graph configuration (transition rules for
/// sequential nodes, consensus config for parallel branches).
///
/// ### Variable contracts
/// - **score** – numeric 0–100, drives {@code ScoreTransition} and consensus scoring
/// - **approved** – boolean, drives {@code ApprovalTransition} and consensus voting
/// - **recommendation** – string, justification for score/approval decisions
///
/// ### Lifecycle – single owner
/// {@link io.hensu.core.execution.pipeline.TransitionPostProcessor} is the sole
/// owner of engine variable cleanup in the state context:
/// - **Forward transition** – clears all engine vars via {@link #all()}
/// - **Backtrack transition** – clears score and approved; keeps recommendation
///   so {@link io.hensu.core.execution.enricher.FeedbackContextInjector} can
///   surface it in the backtracked node's prompt
///
/// No other component (executors, enrichers) should remove engine vars from state.
///
/// ### A human verdict is not an engine variable
/// Engine variables record what the *agent* said about its own output. A human reviewer's
/// decision is a different kind of claim, and it travels on its own channel: a
/// {@link io.hensu.core.review.ReviewVerdict} set on
/// {@link io.hensu.core.state.HensuState} by
/// {@link io.hensu.core.execution.pipeline.ReviewPostProcessor}, which writes no engine
/// variable at all. {@code ApprovalTransition} gives the verdict precedence over {@code
/// approved} when routing, and {@link io.hensu.core.execution.pipeline.TransitionPostProcessor}
/// promotes the reviewer's reason to {@link #RECOMMENDATION} on transitions that carry
/// feedback. Keeping the two apart is what lets the statements above stay true without
/// exception: {@code approved} has exactly two writers, output extraction and consensus, and
/// "the reviewer decides" is a property of the rule that reads them rather than of the order in
/// which processors happen to run.
///
/// @see io.hensu.core.execution.enricher.ScoreVariableInjector
/// @see io.hensu.core.execution.enricher.ApprovalVariableInjector
/// @see io.hensu.core.execution.enricher.RecommendationVariableInjector
/// @see io.hensu.core.execution.parallel.ConsensusEvaluator
/// @see io.hensu.core.execution.pipeline.TransitionPostProcessor
/// @see io.hensu.core.review.ReviewVerdict
/// @see io.hensu.core.execution.pipeline.ReviewPostProcessor
public final class EngineVariables {

    /// Numeric score (0–100) for quality evaluation and consensus.
    public static final String SCORE = "score";

    /// Boolean approval flag for approval transitions and consensus voting.
    public static final String APPROVED = "approved";

    /// Justification string for score/approval decisions.
    public static final String RECOMMENDATION = "recommendation";

    /// All engine variable names used in consensus evaluation.
    ///
    /// These are automatically injected into consensus branch prompts and
    /// extracted from agent output for vote determination.
    public static final List<String> CONSENSUS_KEYS = List.of(SCORE, APPROVED, RECOMMENDATION);

    private static final Set<String> ENGINE_VAR_SET = Set.of(SCORE, APPROVED, RECOMMENDATION);

    private EngineVariables() {}

    /// Returns an unmodifiable set of all engine variable names.
    ///
    /// @return unmodifiable set containing all engine variable names
    public static Set<String> all() {
        return ENGINE_VAR_SET;
    }

    /// Checks whether the given name is a reserved engine variable.
    ///
    /// Use this to prevent user-declared {@code yields()} or {@code writes()}
    /// from colliding with engine-managed output fields.
    ///
    /// @param name variable name to check, may be null
    /// @return true if the name is a reserved engine variable
    public static boolean isEngineVar(String name) {
        return name != null && ENGINE_VAR_SET.contains(name);
    }
}
