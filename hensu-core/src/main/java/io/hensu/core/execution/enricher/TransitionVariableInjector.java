package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;

/// Base for injectors that append an engine output requirement when a transition rule
/// declares a specific engine variable.
///
/// Subclasses define the engine variable name and instruction text as static constants
/// and expose them via {@link #engineVariable()} and {@link #instruction()}. This class
/// provides the shared activation logic: the instruction is appended when at least one
/// transition rule on the node declares the variable via
/// {@link io.hensu.core.workflow.transition.TransitionRule#requiredEngineVars()}, or when
/// the execution context indicates a consensus branch requiring self-scoring.
///
/// Not all {@link EngineVariableInjector} implementations extend this class –
/// {@link RubricPromptInjector}, for example, activates on rubric presence rather than
/// transition rules.
///
/// @see EngineVariableInjector
abstract class TransitionVariableInjector implements EngineVariableInjector {

    /// Returns the engine variable name to match against transition rules.
    ///
    /// @return engine variable name, not null
    protected abstract String engineVariable();

    /// Returns the prompt instruction to append when the variable is required.
    ///
    /// @return instruction text, not null
    protected abstract String instruction();

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        boolean needs =
                node.getTransitionRules().stream()
                                .anyMatch(r -> r.requiredEngineVars().contains(engineVariable()))
                        || (ctx.getBranchConfig() != null
                                && ctx.getBranchConfig().needsSelfScoring());
        return needs ? prompt + instruction() : prompt;
    }
}
