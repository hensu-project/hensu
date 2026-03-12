package io.hensu.core.execution.pipeline;

import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.AgentOutputValidator;
import io.hensu.core.util.JsonUtil;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.ApprovalTransition;
import io.hensu.core.workflow.transition.ScoreTransition;
import java.io.Serial;
import java.util.*;
import java.util.logging.Logger;

/// Routes node execution output into the workflow state context under semantic variable names.
///
/// Performs two operations when a node produces non-null output:
/// 1. Validates the output for safety violations (dangerous chars, Unicode tricks, size)
/// 2. Routes output to state context under the variable names declared in `writes` plus
///    any engine variables implied by the node's transition rules
///
/// ### Engine variables
/// `score`, `approved`, and `recommendation` are extracted automatically based on transitions.
/// `score` → {@link ScoreTransition}, `approved` → {@link ApprovalTransition},
/// `recommendation` → either transition (scoring and approval both require justification).
/// Developers do not declare these in `writes` — the engine infers them from the graph.
///
/// ### Routing Rules
/// - **Single domain write, no engine vars**: if output is JSON containing the declared key,
///   extracts the typed value; otherwise stores the full raw text under `writes.get(0)`
/// - **Multiple writes or engine vars present**: JSON response parsed; each key extracted
/// to context
/// - **No writes**: output stored under the node's own ID for downstream template resolution
///   (e.g. `{step1}`)
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
/// @implNote **Thread-safe.** Stateless; no instance fields. Safe to share across Virtual Threads.
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

        if (node instanceof StandardNode standardNode) {
            List<String> writes = standardNode.getWrites();
            List<String> engineVars = engineVarsFor(standardNode);

            if (!writes.isEmpty() || !engineVars.isEmpty()) {
                List<String> allKeys =
                        new ArrayList<>(
                                new LinkedHashSet<>(writes) {
                                    @Serial
                                    private static final long serialVersionUID =
                                            6441855807738151353L;

                                    {
                                        addAll(engineVars);
                                    }
                                });

                if (engineVars.isEmpty() && writes.size() == 1) {
                    // Single domain write, no engine vars: fall back to raw text if JSON misses key
                    String key = writes.getFirst();
                    Map<String, Object> extracted = new HashMap<>();
                    JsonUtil.extractOutputParams(List.of(key), output, extracted, logger);
                    state.getContext().put(key, extracted.getOrDefault(key, output));
                } else {
                    // Multiple writes or engine vars present: always JSON extraction
                    JsonUtil.extractOutputParams(allKeys, output, state.getContext(), logger);
                }
            } else {
                state.getContext().put(node.getId(), output);
            }
        } else if (node instanceof GenericNode) {
            state.getContext().put(node.getId(), output);
        }

        return Optional.empty();
    }

    private List<String> engineVarsFor(StandardNode node) {
        List<String> vars = new ArrayList<>();
        boolean hasScore = false;
        boolean hasApproval = false;
        for (var rule : node.getTransitionRules()) {
            if (rule instanceof ScoreTransition) {
                vars.add("score");
                hasScore = true;
            } else if (rule instanceof ApprovalTransition) {
                vars.add("approved");
                hasApproval = true;
            }
        }
        if (hasScore || hasApproval) vars.add("recommendation");
        return vars;
    }

    private Optional<ExecutionResult> rejectOutput(HensuState state, String nodeId, String reason) {
        logger.warning("Rejecting output from node [" + nodeId + "]: " + reason);
        return Optional.of(
                new ExecutionResult.Failure(
                        state,
                        new IllegalStateException("Node [" + nodeId + "] output " + reason)));
    }
}
