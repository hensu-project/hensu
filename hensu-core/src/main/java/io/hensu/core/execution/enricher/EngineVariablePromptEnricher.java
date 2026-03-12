package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import java.util.List;

/// Composite enricher that runs all registered {@link EngineVariableInjector}s in order.
///
/// Each injector is self-contained: it inspects the node and execution context to decide
/// whether it applies, then appends its format instruction. The enricher is a dumb iterator —
/// all conditional logic lives inside the injectors.
///
/// ### Default pipeline
/// {@link #DEFAULT} runs in this order:
/// 1. {@link RubricPromptInjector} — injects rubric criteria when `node.getRubricId()` is set
/// 2. {@link ScoreVariableInjector} — injects `score` requirement when a
///    {@link io.hensu.core.workflow.transition.ScoreTransition} exists
/// 3. {@link ApprovalVariableInjector} — injects `approved` requirement when an
///    {@link io.hensu.core.workflow.transition.ApprovalTransition} exists
/// 4. {@link RecommendationVariableInjector} — injects `recommendation` requirement when a
///    {@link io.hensu.core.workflow.transition.ScoreTransition} or
///    {@link io.hensu.core.workflow.transition.ApprovalTransition} exists
/// 5. {@link WritesVariableInjector} — injects field requirements for all user-declared
///    {@code writes()} variables so the LLM produces extractable JSON keys
///
/// ### Extension
/// Construct a custom enricher with additional {@link EngineVariableInjector} implementations.
///
/// @implNote **Immutable after construction.** All fields are final; safe to share across
/// Virtual Threads.
///
/// @see EngineVariableInjector
public final class EngineVariablePromptEnricher {

    /// Default enricher covering all built-in engine output requirements.
    public static final EngineVariablePromptEnricher DEFAULT =
            new EngineVariablePromptEnricher(
                    List.of(
                            new RubricPromptInjector(),
                            new ScoreVariableInjector(),
                            new ApprovalVariableInjector(),
                            new RecommendationVariableInjector(),
                            new WritesVariableInjector()));

    private final List<EngineVariableInjector> injectors;

    /// Constructs an enricher with the given injectors.
    ///
    /// @param injectors ordered list of injectors to apply, not null
    public EngineVariablePromptEnricher(List<EngineVariableInjector> injectors) {
        this.injectors = List.copyOf(injectors);
    }

    /// Enriches the prompt by running all registered injectors in order.
    ///
    /// Each injector receives the current prompt and decides independently whether to append
    /// its instruction. Instructions accumulate in registration order.
    ///
    /// @param prompt the fully resolved prompt, not null
    /// @param node   the node being executed, not null
    /// @param ctx    execution context providing rubric engine, workflow, and state, not null
    /// @return enriched prompt with all applicable format instructions appended, never null
    public String enrich(String prompt, Node node, ExecutionContext ctx) {
        String result = prompt;
        for (EngineVariableInjector injector : injectors) {
            result = injector.inject(result, node, ctx);
        }
        return result;
    }
}
