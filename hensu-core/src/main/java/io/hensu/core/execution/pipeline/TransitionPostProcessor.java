package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.parallel.ConsensusResult;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.BoundedTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashSet;
import java.util.Set;
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
                applyTransitionEffects(state, node, rule);
                return target;
            }
            // A failed node never produced its routed variables — mismatch diagnostics
            // would be noise on top of the node's own error.
            if (context.result().getStatus() != ResultStatus.SUCCESS) {
                continue;
            }
            String diagnostic = rule.mismatchDiagnostic(state);
            if (diagnostic != null) {
                context.executionContext()
                        .getListener()
                        .onTransitionWarning(node.getId(), diagnostic);
            }
        }

        throw new IllegalStateException("No valid transition from " + node.getId());
    }

    /// Applies retry-counter, feedback, and engine var cleanup after a transition rule matches.
    ///
    /// Engine variable lifecycle is centralized here — no other component clears engine vars
    /// from the state context. The routing clear-set is derived from the node's rules'
    /// {@link TransitionRule#requiredEngineVars()} (minus recommendation, which has its own
    /// feedback lifecycle) plus the built-in score/approved pair, so declared condition
    /// variables cannot leak into a later node routing on the same name. Three paths:
    /// - **Backtrack** (bounded, under budget): clear routing vars. Feedback handling follows
    ///   {@link TransitionRule#retryFeedback()}: keep recommendation for
    ///   {@link io.hensu.core.execution.enricher.FeedbackContextInjector}, clear it when no
    ///   agent feedback exists, or synthesize it from consensus vote details.
    /// - **Forward with feedback** ({@link TransitionRule#withFeedback()} is true): clear
    ///   routing vars but keep recommendation so the target node sees evaluation context.
    /// - **Forward** (default): clear routing vars and recommendation.
    ///
    /// @param state current workflow state (mutated in place)
    /// @param node the node being transitioned away from
    /// @param rule the transition rule that matched
    private void applyTransitionEffects(HensuState state, Node node, TransitionRule rule) {
        String nodeId = state.getCurrentNode();
        Set<String> routingVars = routingVarsFor(node);
        if (rule instanceof BoundedTransition bt && bt.underBudget(state)) {
            state.incrementRetryCount(bt.namespace(), nodeId);
            switch (rule.retryFeedback()) {
                case CONSENSUS -> injectConsensusFeedback(state, nodeId);
                case NONE -> state.getContext().remove(EngineVariables.RECOMMENDATION);
                case RECOMMENDATION -> {}
            }
            state.getContext().keySet().removeAll(routingVars);
            return;
        }
        state.resetRetryCounts(nodeId);
        state.getContext().keySet().removeAll(routingVars);
        if (!rule.withFeedback()) {
            state.getContext().remove(EngineVariables.RECOMMENDATION);
        }
    }

    /// Derives the routing-variable clear-set for a node: every engine variable its rules
    /// declare via {@link TransitionRule#requiredEngineVars()} plus the built-in
    /// score/approved pair, minus recommendation (dedicated feedback lifecycle).
    ///
    /// @param node the node whose rules define the routing variables
    /// @return mutable set of variable names to clear, never null
    private Set<String> routingVarsFor(Node node) {
        Set<String> vars = new HashSet<>();
        for (TransitionRule rule : node.getTransitionRules()) {
            vars.addAll(rule.requiredEngineVars());
        }
        vars.add(EngineVariables.SCORE);
        vars.add(EngineVariables.APPROVED);
        vars.remove(EngineVariables.RECOMMENDATION);
        return vars;
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
