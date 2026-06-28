package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.transition.BoundedTransition;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.NoConsensusTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.stream.Collectors;

/// Evaluates transition rules to determine the next node after execution.
///
/// Applies three transition strategies in priority order:
/// 1. **Loop break override** — if `state.loopBreakTarget` is set, consumes it
///    and redirects immediately (one-shot)
/// 2. **Loop exit** — if the current node is a {@link LoopNode}, reads
///    `loop_exit_target` from the state context
/// 3. **Rule evaluation** — iterates the node's {@link TransitionRule} list
///    in order; the first non-null target wins
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE} after setting
///   the next node in state
/// - **Side effects**: Mutates `state.currentNode`, clears `state.loopBreakTarget`
///   when consumed, clears `state.activePlan` when leaving the node
///
/// @implNote Stateless. No constructor dependencies. Safe to reuse across
/// loop iterations.
///
/// @see TransitionRule for individual rule evaluation contract
/// @see LoopNode for loop-specific transition handling
public final class TransitionPostProcessor implements PostNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "TransitionPostProcessor";

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        var state = context.state();

        if (state.isNodeRedirected()) {
            state.setNodeRedirected(false);
        } else {
            state.setCurrentNode(resolveNextNode(context));
        }

        state.setActivePlan(null);

        return ProcessorOutcome.CONTINUE;
    }

    /// Resolves the next node id according to the three transition strategies.
    ///
    /// @return next node id, never null
    /// @throws IllegalStateException if no strategy yields a target
    private String resolveNextNode(ProcessorContext context) {
        var state = context.state();
        var node = context.currentNode();

        String loopBreakTarget = state.getLoopBreakTarget();
        if (loopBreakTarget != null) {
            state.setLoopBreakTarget(null);
            return loopBreakTarget;
        }

        if (node instanceof LoopNode) {
            String loopExit = (String) state.getContext().remove("loop_exit_target");
            if (loopExit != null) {
                return loopExit;
            }
        }

        for (TransitionRule rule : node.getTransitionRules()) {
            String target = rule.evaluate(state, context.result());
            if (target != null) {
                applyTransitionEffects(state, rule);
                return target;
            }
        }

        throw new IllegalStateException("No valid transition from " + node.getId());
    }

    /// Applies retry-counter, feedback, and engine var cleanup after a transition rule matches.
    ///
    /// Engine variable lifecycle is centralized here — no other component clears engine vars
    /// from the state context. Three paths:
    /// - **Backtrack** (bounded, under budget): clear routing vars (score, approved). Keep
    ///   recommendation for {@link io.hensu.core.execution.enricher.FeedbackContextInjector},
    ///   except for failure retries where no agent feedback exists.
    /// - **Forward with feedback** ({@link TransitionRule#withFeedback()} is true): clear
    ///   routing vars but keep recommendation so the target node sees evaluation context.
    /// - **Forward** (default): clear all engine vars.
    ///
    /// @param state current workflow state (mutated in place)
    /// @param rule the transition rule that matched
    private void applyTransitionEffects(HensuState state, TransitionRule rule) {
        String nodeId = state.getCurrentNode();
        if (rule instanceof BoundedTransition bt && bt.underBudget(state)) {
            state.incrementRetryCount(bt.namespace(), nodeId);
            if (bt.inner() instanceof NoConsensusTransition) {
                injectConsensusFeedback(state, nodeId);
            }
            state.getContext().remove(EngineVariables.SCORE);
            state.getContext().remove(EngineVariables.APPROVED);
            if (bt.inner() instanceof FailureTransition) {
                state.getContext().remove(EngineVariables.RECOMMENDATION);
            }
            return;
        }
        state.resetRetryCounts(nodeId);
        if (rule.withFeedback()) {
            state.getContext().remove(EngineVariables.SCORE);
            state.getContext().remove(EngineVariables.APPROVED);
        } else {
            EngineVariables.all().forEach(state.getContext().keySet()::remove);
        }
    }

    /// Injects prior-round consensus feedback into the state context so the producer
    /// agent can see why consensus failed and adjust its output.
    ///
    /// Reads the {@code consensus_result:<nodeId>} context key written by
    /// {@code ParallelNodeExecutor} and formats vote details into
    /// {@link EngineVariables#RECOMMENDATION}. For {@code JUDGE_DECIDES} strategies the
    /// judge's reasoning is used directly; for algorithmic strategies each vote's output
    /// is joined with separator lines.
    ///
    /// @param state current workflow state (context is mutated)
    /// @param nodeId the parallel node whose consensus failed
    private void injectConsensusFeedback(HensuState state, String nodeId) {
        Object raw = state.getContext().remove("consensus_result:" + nodeId);
        if (!(raw instanceof ConsensusResult cr)) {
            return;
        }

        String feedback;
        if (cr.strategyUsed() == ConsensusStrategy.JUDGE_DECIDES && cr.reasoning() != null) {
            feedback = cr.reasoning();
        } else {
            feedback =
                    cr.votes().values().stream()
                            .map(v -> v.branchId() + " (" + v.voteType() + "): " + v.output())
                            .collect(Collectors.joining("\n---\n"));
        }

        if (!feedback.isBlank()) {
            state.getContext().put(EngineVariables.RECOMMENDATION, feedback);
        }
    }
}
