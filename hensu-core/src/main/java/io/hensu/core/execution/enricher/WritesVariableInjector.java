package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import java.util.List;
import java.util.Optional;

/// Injects JSON field requirements for user-declared {@code writes()} variables into the prompt.
///
/// When a node declares output variables via {@code writes("article", "recommendation")}, the LLM
/// must be explicitly told to include those exact fields in its JSON response. Without this
/// instruction the LLM produces arbitrary field names and
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} cannot extract them,
/// leaving downstream prompt variables empty (e.g. {@code {article}} resolves to an empty string).
///
/// ### Description enrichment
/// When the workflow state schema declares a {@code description} for a variable, the injected
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
/// ### Placement in the pipeline
/// Runs last in {@link EngineVariablePromptEnricher#DEFAULT}, after
/// {@link ScoreVariableInjector} and {@link ApprovalVariableInjector}, so all engine-level
/// format requirements are co-located at the end of the prompt.
///
/// ### Skipped when
/// - The node has no {@code writes()} declarations, or
/// - The node is not a {@link StandardNode}.
///
/// @see EngineVariableInjector
/// @see io.hensu.core.execution.pipeline.OutputExtractionPostProcessor
/// @see io.hensu.core.workflow.state.StateVariableDeclaration#description()
public final class WritesVariableInjector implements EngineVariableInjector {

    @Override
    public String inject(String prompt, Node node, ExecutionContext ctx) {
        if (!(node instanceof StandardNode sn)) return prompt;
        List<String> writes = sn.getWrites();
        if (writes == null || writes.isEmpty()) return prompt;

        WorkflowStateSchema schema =
                ctx.getWorkflow() != null ? ctx.getWorkflow().getStateSchema() : null;

        StringBuilder fields = new StringBuilder();
        for (String field : writes) {
            Optional<String> desc = schema != null ? schema.descriptionOf(field) : Optional.empty();
            fields.append("\n  \"").append(field).append("\"");
            desc.ifPresent(d -> fields.append(" — ").append(d));
        }

        return prompt + "\n\nEngine output requirement: your JSON response MUST include:" + fields;
    }
}
