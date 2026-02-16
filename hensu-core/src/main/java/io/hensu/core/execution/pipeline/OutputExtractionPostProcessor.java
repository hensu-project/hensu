package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Optional;
import java.util.logging.Logger;

/// Extracts node execution output into the workflow state context.
///
/// Performs two operations when a node produces non-null output:
/// 1. Stores the raw output string keyed by node ID in the context map
/// 2. For {@link StandardNode}s with `outputParams`, parses JSON output
///    and extracts named parameters into the context map
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Always returns empty (never short-circuits)
/// - **Side effects**: Mutates `context.state().getContext()` map
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see JsonUtil#extractOutputParams for JSON parameter extraction
public final class OutputExtractionPostProcessor implements PostNodeExecutionProcessor {

    private static final Logger logger =
            Logger.getLogger(OutputExtractionPostProcessor.class.getName());

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        var result = context.result();
        var state = context.state();
        var node = context.currentNode();

        if (result.getOutput() != null) {
            state.getContext().put(node.getId(), result.getOutput().toString());

            if (node instanceof StandardNode standardNode
                    && !standardNode.getOutputParams().isEmpty()) {
                JsonUtil.extractOutputParams(
                        standardNode.getOutputParams(),
                        result.getOutput().toString(),
                        state.getContext(),
                        logger);
            }
        }

        return Optional.empty();
    }
}
