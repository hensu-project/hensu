package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;

/// Contract for injecting engine-managed output format instructions into a node prompt.
///
/// Each implementation is self-contained: it inspects the node's configuration and
/// execution context to decide whether injection is applicable, then appends the
/// appropriate format instruction to the prompt.
///
/// Implementations are registered in {@link EngineVariablePromptEnricher} and invoked
/// for every node execution. Each injector must guard itself — returning the original
/// prompt unchanged when its condition is not met.
///
/// ### Extension point
/// Add a new implementation to support additional engine-managed output requirements.
/// Register it in {@link EngineVariablePromptEnricher#DEFAULT}.
///
/// @see EngineVariablePromptEnricher for composite execution
/// @see ScoreVariableInjector
/// @see ApprovalVariableInjector
/// @see RecommendationVariableInjector
/// @see RubricPromptInjector
public interface EngineVariableInjector {

    /// Conditionally appends an output format instruction to the given prompt.
    ///
    /// Implementations inspect `node` and `ctx` to determine whether injection
    /// is applicable. If not applicable, the original `prompt` is returned unchanged.
    ///
    /// @param prompt the fully resolved prompt to enrich, not null
    /// @param node   the node being executed, not null
    /// @param ctx    execution context providing rubric engine, workflow, and state, not null
    /// @return enriched prompt with format instruction appended, or original prompt if not
    ///     applicable, never null
    String inject(String prompt, Node node, ExecutionContext ctx);
}
