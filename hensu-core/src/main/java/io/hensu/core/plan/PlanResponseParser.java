package io.hensu.core.plan;

import java.util.List;

/// Parses LLM-generated plan responses into structured {@link PlannedStep} lists.
///
/// Decouples plan-response parsing from any specific JSON library so that
/// {@code hensu-core} remains dependency-free. The Jackson-based implementation
/// lives in {@code hensu-serialization} as {@code JacksonPlanResponseParser}.
///
/// ### Supported step shapes (JSON)
///
/// **Tool call** — invokes a registered tool:
/// ```json
/// {"tool": "fetch_order", "arguments": {"id": "{orderId}"}, "description": "Fetch order"}
/// ```
///
/// **Synthesize** — asks the node agent to summarise results:
/// ```json
/// {"synthesize": true, "description": "Summarise all results into a recommendation"}
/// ```
///
/// The parser sets `agentId = null` on {@link PlanStepAction.Synthesize} steps;
/// the executor enriches them with the node's configured agent after creation.
///
/// @see io.hensu.core.plan.LlmPlanner for the primary caller
/// @see PlannedStep for the parsed output type
public interface PlanResponseParser {

    /// Parses a raw LLM response string into a list of planned steps.
    ///
    /// The content may be wrapped in markdown code fences (` ```json `);
    /// implementations must strip them before deserialisation.
    ///
    /// @param content raw LLM response, not null
    /// @return list of planned steps in order, never null, may be empty
    /// @throws PlanCreationException if the content cannot be parsed as a valid
    ///                               step list
    List<PlannedStep> parse(String content) throws PlanCreationException;
}
