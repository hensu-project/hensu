package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.transition.TransitionRule;
import java.util.Optional;

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
/// - **Postcondition**: Always returns empty after setting the next node in state
/// - **Side effects**: Mutates `state.currentNode`, clears `state.loopBreakTarget`
///   when consumed
///
/// @implNote Stateless. No constructor dependencies. Safe to reuse across
/// loop iterations.
///
/// @see TransitionRule for individual rule evaluation contract
/// @see LoopNode for loop-specific transition handling
public final class TransitionPostProcessor implements PostNodeExecutionProcessor {

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        var state = context.state();
        var node = context.currentNode();

        // If a prior processor (e.g. ReviewPostProcessor backtrack) already
        // redirected execution, do not overwrite with normal transition logic.
        if (!state.getCurrentNode().equals(node.getId())) {
            return Optional.empty();
        }

        String loopBreakTarget = state.getLoopBreakTarget();
        if (loopBreakTarget != null) {
            state.setLoopBreakTarget(null);
            state.setCurrentNode(loopBreakTarget);
            return Optional.empty();
        }

        if (node instanceof LoopNode) {
            String loopExit = (String) state.getContext().get("loop_exit_target");
            if (loopExit != null) {
                state.setCurrentNode(loopExit);
                return Optional.empty();
            }
        }

        for (TransitionRule rule : node.getTransitionRules()) {
            String target = rule.evaluate(state, context.result());
            if (target != null) {
                state.setCurrentNode(target);
                return Optional.empty();
            }
        }

        throw new IllegalStateException("No valid transition from " + node.getId());
    }
}
