package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import java.util.List;

/// Injects JSON field requirements for user-declared {@code writes()} variables into the prompt.
///
/// When a node declares output variables via {@code writes("article", "recommendation")}, the LLM
/// must be explicitly told to include those exact fields in its JSON response. Without this
/// instruction the LLM produces arbitrary field names and
/// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor} cannot extract them,
/// leaving downstream prompt variables empty (e.g. {@code {article}} resolves to an empty string).
///
/// ### Skipped when
/// - The node has no {@code writes()} declarations, or
/// - The node is not a {@link StandardNode}.
///
/// @see BaseVariableInjector
/// @see io.hensu.core.execution.pipeline.OutputExtractionPostProcessor
/// @see io.hensu.core.workflow.state.StateVariableDeclaration#description()
public final class WritesVariableInjector extends BaseVariableInjector {

    @Override
    protected List<String> keysFor(Node node, ExecutionContext ctx) {
        if (!(node instanceof StandardNode sn)) return List.of();
        List<String> writes = sn.getWrites();
        return writes != null ? writes : List.of();
    }

    @Override
    protected String requirementPrefix() {
        return "Engine output requirement";
    }
}
