package io.hensu.core.execution.enricher;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.parallel.BranchExecutionConfig;
import io.hensu.core.workflow.node.Node;
import java.util.List;

/// Injects JSON field requirements for branch {@code yields()} variables into the prompt.
///
/// When a parallel branch declares output variables via {@code yields("api_schema", "summary")},
/// the LLM must be explicitly told to include those exact fields in its JSON response so
/// the extraction pipeline can pull them out.
///
/// The yields list is read from the typed {@link BranchExecutionConfig} on the
/// {@link ExecutionContext}, set by {@code ParallelNodeExecutor} before branch enrichment.
///
/// ### Skipped when
/// - No branch config on the execution context, or
/// - The yields list is empty.
///
/// @see BaseVariableInjector
/// @see BranchExecutionConfig
/// @see io.hensu.core.execution.pipeline.OutputExtractionPostProcessor
public final class YieldsVariableInjector extends BaseVariableInjector {

    @Override
    protected List<String> keysFor(Node node, ExecutionContext ctx) {
        BranchExecutionConfig config = ctx.getBranchConfig();
        if (config == null) return List.of();
        return config.yields();
    }

    @Override
    protected String requirementPrefix() {
        return "Engine yield requirement";
    }
}
