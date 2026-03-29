package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import java.util.List;
import java.util.Optional;

/// Shared logic for injecting JSON field requirements into the prompt.
///
/// Iterates over a list of field names, looks up each field's description in the
/// workflow state schema, and appends a formatted requirement block to the prompt.
/// Subclasses provide the field names and the requirement label.
///
/// ### Description enrichment
/// When the state schema declares a {@code description} for a variable, the injected
/// requirement includes it so the LLM knows exactly what content to produce:
///
/// ```
/// Engine output requirement: your JSON response MUST include:
///   "article"        — the full written article text
///   "recommendation" — improvement feedback for the next iteration
/// ```
///
/// When no description is declared, the field name alone is emitted (graceful fallback).
///
/// @see WritesVariableInjector
/// @see YieldsVariableInjector
/// @see EngineVariableInjector
public abstract class BaseVariableInjector implements EngineVariableInjector {

    @Override
    public final String inject(String prompt, Node node, ExecutionContext ctx) {
        List<String> keys = keysFor(node, ctx);
        if (keys == null || keys.isEmpty()) return prompt;

        WorkflowStateSchema schema =
                ctx.getWorkflow() != null ? ctx.getWorkflow().getStateSchema() : null;

        StringBuilder fields = new StringBuilder();
        for (String field : keys) {
            Optional<String> desc = schema != null ? schema.descriptionOf(field) : Optional.empty();
            fields.append("\n  \"").append(field).append("\"");
            desc.ifPresent(d -> fields.append(" — ").append(d));
        }

        return prompt
                + "\n\n"
                + requirementPrefix()
                + ": your JSON response MUST include:"
                + fields;
    }

    /// Returns the field names to inject for the given node and context.
    ///
    /// @param node the node being executed, not null
    /// @param ctx  execution context, not null
    /// @return list of field names, or null/empty to skip injection
    protected abstract List<String> keysFor(Node node, ExecutionContext ctx);

    /// Returns the human-readable prefix for the requirement block.
    ///
    /// @return requirement label, e.g. "Engine output requirement"
    protected abstract String requirementPrefix();
}
