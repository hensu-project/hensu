package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.AgentOutputValidator;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.node.StandardNode;
import java.util.Optional;
import java.util.logging.Logger;

/// Extracts node execution output into the workflow state context.
///
/// Performs three operations when a node produces non-null output:
/// 1. Validates the output for safety violations (dangerous chars, Unicode tricks, size)
/// 2. Stores the raw output string keyed by node ID in the context map
/// 3. For {@link StandardNode}s with `outputParams`, parses JSON output
///    and extracts named parameters into the context map
///
/// ### Contracts
/// - **Precondition**: `context.result()` is non-null (post-execution pipeline)
/// - **Postcondition**: Returns empty on success; returns `Failure` if output fails validation
/// - **Side effects**: Mutates `context.state().getContext()` map only on success
///
/// ### Validation
/// LLM-generated outputs are non-deterministic and treated as untrusted. Before storing
/// output in the context, this processor rejects:
/// - Outputs containing dangerous ASCII control characters
/// - Outputs containing Unicode manipulation characters (RTL overrides, zero-width chars, BOM)
/// - Outputs exceeding {@link AgentOutputValidator#MAX_LLM_OUTPUT_BYTES} (4 MB)
///
/// @implNote Stateless. Safe to reuse across loop iterations.
///
/// @see AgentOutputValidator for validation predicates
/// @see JsonUtil#extractOutputParams for JSON parameter extraction
public final class OutputExtractionPostProcessor implements PostNodeExecutionProcessor {

    private static final Logger logger =
            Logger.getLogger(OutputExtractionPostProcessor.class.getName());

    @Override
    public Optional<ExecutionResult> process(ProcessorContext context) {
        var result = context.result();
        var state = context.state();
        var node = context.currentNode();

        if (result.getOutput() == null) {
            return Optional.empty();
        }

        String output = result.getOutput().toString();

        if (AgentOutputValidator.containsDangerousChars(output)) {
            return rejectOutput(state, node.getId(), "contains illegal control characters");
        }

        if (AgentOutputValidator.containsUnicodeTricks(output)) {
            return rejectOutput(state, node.getId(), "contains Unicode manipulation characters");
        }

        if (AgentOutputValidator.exceedsSizeLimit(
                output, AgentOutputValidator.MAX_LLM_OUTPUT_BYTES)) {
            return rejectOutput(state, node.getId(), "exceeds maximum allowed size");
        }

        state.getContext().put(node.getId(), output);

        if (node instanceof StandardNode standardNode
                && !standardNode.getOutputParams().isEmpty()) {
            JsonUtil.extractOutputParams(
                    standardNode.getOutputParams(), output, state.getContext(), logger);
        }

        return Optional.empty();
    }

    private Optional<ExecutionResult> rejectOutput(HensuState state, String nodeId, String reason) {
        logger.warning("Rejecting output from node [" + nodeId + "]: " + reason);
        return Optional.of(
                new ExecutionResult.Failure(
                        state,
                        new IllegalStateException("Node [" + nodeId + "] output " + reason)));
    }
}
