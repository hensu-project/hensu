package io.hensu.core.execution.pipeline;

import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.transition.TransitionRule;

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
            String loopExit = (String) state.getContext().get("loop_exit_target");
            if (loopExit != null) {
                return loopExit;
            }
        }

        for (TransitionRule rule : node.getTransitionRules()) {
            String target = rule.evaluate(state, context.result());
            if (target != null) {
                return target;
            }
        }

        throw new IllegalStateException("No valid transition from " + node.getId());
    }
}
