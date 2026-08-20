package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewVerdict;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.transition.BoundedTransition;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.HashSet;
import java.util.Set;

/// Evaluates transition rules to determine the next node after execution.
///
/// Iterates the node's {@link TransitionRule} list in order; the first non-null
/// target wins.
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Always returns {@link ProcessorOutcome#CONTINUE} after setting
///   the next node in state
/// - **Side effects**: Mutates `state.currentNode`
///
/// @implNote Stateless. No constructor dependencies. Safe to reuse across
/// loop iterations.
///
/// @see TransitionRule for individual rule evaluation contract
public final class TransitionPostProcessor implements PostNodeExecutionProcessor {

    public static final String PROCESSOR_ID = "TransitionPostProcessor";

    @Override
    public String id() {
        return PROCESSOR_ID;
    }

    @Override
    public ProcessorOutcome process(ProcessorContext context) {
        var state = context.state();

        try {
            if (state.isNodeRedirected()) {
                state.setNodeRedirected(false);
                // A redirect from human review is feedback by definition — the reviewer sent
                // the workflow back with a reason attached.
                promoteVerdictReason(state);
            } else {
                state.setCurrentNode(resolveNextNode(context));
            }
        } finally {
            // A verdict describes one review decision and must never outlive the transition
            // it routed, even when rule evaluation threw.
            state.setReviewVerdict(null);
        }

        return ProcessorOutcome.CONTINUE;
    }

    /// Promotes the reviewer's reason to {@link EngineVariables#RECOMMENDATION} so
    /// {@link io.hensu.core.execution.enricher.FeedbackContextInjector} surfaces it in the next
    /// node's prompt, overriding whatever justification the agent wrote for itself.
    ///
    /// A blank or absent reason leaves the context untouched: an unexplained rejection should
    /// not blank out feedback that a rubric or the agent already supplied.
    ///
    /// @param state current workflow state (context is mutated)
    private void promoteVerdictReason(HensuState state) {
        ReviewVerdict verdict = state.getReviewVerdict();
        if (verdict == null || verdict.reason() == null || verdict.reason().isBlank()) {
            return;
        }
        state.getContext().put(EngineVariables.RECOMMENDATION, verdict.reason());
    }

    /// Resolves the next node id by evaluating the node's transition rules in order.
    ///
    /// @return next node id, never null
    /// @throws IllegalStateException if no rule yields a target
    private String resolveNextNode(ProcessorContext context) {
        var state = context.state();
        var node = context.currentNode();

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
    /// On every path that preserves feedback, a human reviewer's reason takes precedence over
    /// the agent's own recommendation — see {@link #promoteVerdictReason(HensuState)}.
    ///
    /// Engine variable lifecycle is centralized here — no other component clears engine vars
    /// from the state context. The routing clear-set is derived from the node's rules'
    /// {@link TransitionRule#requiredRoutingVars()} (minus recommendation, which has its own
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
                case RECOMMENDATION -> promoteVerdictReason(state);
            }
            state.getContext().keySet().removeAll(routingVars);
            return;
        }
        state.resetRetryCounts(nodeId);
        state.getContext().keySet().removeAll(routingVars);
        if (rule.withFeedback()) {
            promoteVerdictReason(state);
        } else {
            state.getContext().remove(EngineVariables.RECOMMENDATION);
        }
    }

    /// Derives the routing-variable clear-set for a node: every engine variable its rules
    /// declare via {@link TransitionRule#requiredRoutingVars()} plus the built-in
    /// score/approved pair, minus recommendation (dedicated feedback lifecycle).
    ///
    /// @param node the node whose rules define the routing variables
    /// @return mutable set of variable names to clear, never null
    private Set<String> routingVarsFor(Node node) {
        Set<String> vars = new HashSet<>();
        for (TransitionRule rule : node.getTransitionRules()) {
            vars.addAll(rule.requiredRoutingVars());
        }
        vars.add(EngineVariables.SCORE);
        vars.add(EngineVariables.APPROVED);
        vars.remove(EngineVariables.RECOMMENDATION);
        return vars;
    }

    /// Injects prior-round consensus feedback into the state context so the producer
    /// agent can see why consensus failed and adjust its output.
    ///
    /// Reads the {@code consensus_feedback:<nodeId>} context key — a pre-formatted,
    /// snapshot-safe string written by {@code ParallelNodeExecutor} when consensus
    /// fails — and promotes it to {@link EngineVariables#RECOMMENDATION}.
    ///
    /// @param state current workflow state (context is mutated)
    /// @param nodeId the parallel node whose consensus failed
    private void injectConsensusFeedback(HensuState state, String nodeId) {
        Object feedback = state.getContext().remove("consensus_feedback:" + nodeId);
        if (feedback instanceof String s && !s.isBlank()) {
            state.getContext().put(EngineVariables.RECOMMENDATION, s);
        }
    }
}
