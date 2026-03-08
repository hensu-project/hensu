package io.hensu.core.execution.enricher;

/// Contract for injecting engine variable output format instructions into a node prompt.
///
/// When a node declares an engine variable in its `writes` list, the engine must ensure
/// the agent understands the required output format. Implementations of this interface
/// append a concise, machine-readable instruction to the resolved prompt so the LLM
/// produces structured output that the extraction post-processor can reliably parse.
///
/// ### Extension point
/// Add a new implementation to support additional engine variables. Register it in
/// {@link EngineVariablePromptEnricher#DEFAULT} alongside the existing injectors.
///
/// @see EngineVariablePromptEnricher for composite execution
/// @see io.hensu.core.workflow.state.WorkflowStateSchema#ENGINE_VARIABLES for the list of
///     recognized engine variable names
public interface EngineVariableInjector {

    /// Returns the engine variable name this injector handles (e.g., `"approved"`, `"score"`).
    ///
    /// @return variable name, never null
    String variableName();

    /// Appends an output format instruction to the given prompt.
    ///
    /// @param prompt the fully resolved prompt to enrich, not null
    /// @return enriched prompt with the format instruction appended, never null
    String inject(String prompt);
}
