package io.hensu.core.execution.enricher;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Composite enricher that appends engine variable output instructions to a resolved prompt.
///
/// Iterates the node's `writes` list and, for each name that matches a registered
/// {@link EngineVariableInjector}, appends the corresponding format instruction. Instructions
/// are appended in the order the variable names appear in `writes`.
///
/// ### Default instance
/// {@link #DEFAULT} covers all built-in engine variables (`score`, `approved`). Use it
/// directly in {@link io.hensu.core.execution.executor.StandardNodeExecutor}; no wiring
/// through {@link io.hensu.core.execution.executor.ExecutionContext} is required because
/// the set of engine variables is fixed at compile time.
///
/// ### Extension
/// To register a new engine variable, add an {@link EngineVariableInjector} implementation
/// and include it in a custom enricher instance.
///
/// @see EngineVariableInjector
/// @see io.hensu.core.workflow.state.WorkflowStateSchema#ENGINE_VARIABLES
public final class EngineVariablePromptEnricher {

    /// Default enricher covering all built-in engine variables.
    public static final EngineVariablePromptEnricher DEFAULT =
            new EngineVariablePromptEnricher(
                    List.of(new ScoreVariableInjector(), new ApprovalVariableInjector()));

    private final Map<String, EngineVariableInjector> injectors;

    /// Constructs an enricher from a list of injectors.
    ///
    /// @param injectors list of injectors indexed by {@link EngineVariableInjector#variableName()},
    ///                  not null, no duplicates
    public EngineVariablePromptEnricher(List<EngineVariableInjector> injectors) {
        this.injectors =
                injectors.stream()
                        .collect(
                                Collectors.toMap(
                                        EngineVariableInjector::variableName, Function.identity()));
    }

    /// Enriches the prompt by appending instructions for each engine variable in `writes`.
    ///
    /// Variables not matched by any registered injector are silently skipped.
    ///
    /// @param prompt the fully resolved prompt, not null
    /// @param writes the list of variable names the node writes, not null
    /// @return enriched prompt, or the original prompt if no engine variables match
    public String enrich(String prompt, List<String> writes) {
        String result = prompt;
        for (String name : writes) {
            EngineVariableInjector injector = injectors.get(name);
            if (injector != null) {
                result = injector.inject(result);
            }
        }
        return result;
    }
}
