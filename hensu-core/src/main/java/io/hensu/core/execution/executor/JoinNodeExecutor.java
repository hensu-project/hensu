package io.hensu.core.execution.executor;

import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/// Passthrough executor for join nodes.
///
/// Results are already in state when this executor runs – placed there by
/// {@link ForkNodeExecutor} which owns the entire structured concurrency
/// lifecycle (fork → join → merge → store). The join node serves as:
///
/// - **Graph boundary marker** for {@link io.hensu.core.execution.WorkflowExecutor#executeUntil}
/// - **Transition rules holder** – determines where execution goes after merge
/// - **Merge configuration holder** – {@link ForkNodeExecutor} reads merge strategy,
///   timeout, and output field from the join node
/// - **Failure routing** – when {@code failOnAnyError} is true and branches failed,
///   returns FAILURE so the join's failure transitions activate
///
/// @see ForkNodeExecutor for the actual fork/join/merge logic
public class JoinNodeExecutor implements NodeExecutor<JoinNode> {

    private static final Logger logger = Logger.getLogger(JoinNodeExecutor.class.getName());

    @Override
    public Class<JoinNode> getNodeType() {
        return JoinNode.class;
    }

    @Override
    public NodeResult execute(JoinNode node, ExecutionContext context) throws Exception {
        Map<String, Object> ctx = context.getState().getContext();
        List<String> writes = node.getWrites();

        // Determine if merged output is present
        boolean hasOutput;
        Object output;
        if (node.getMergeStrategy() == MergeStrategy.MERGE_MAPS) {
            hasOutput = writes.stream().allMatch(ctx::containsKey);
            Map<String, Object> collected = new LinkedHashMap<>();
            for (String var : writes) {
                if (ctx.containsKey(var)) collected.put(var, ctx.get(var));
            }
            output = collected;
        } else {
            output = ctx.get(writes.getFirst());
            hasOutput = output != null;
        }

        // Check for sub-flow failures (placed by ForkNodeExecutor)
        boolean hasFailed = ctx.containsKey(ForkNodeExecutor.FAILED_BRANCHES);
        boolean failOnError = node.isFailOnAnyError() && hasFailed;

        ResultStatus status;
        if (failOnError) {
            List<?> failed = (List<?>) ctx.get(ForkNodeExecutor.FAILED_BRANCHES);
            logger.warning("Join '" + node.getId() + "' failing – sub-flow failures: " + failed);
            status = ResultStatus.FAILURE;
        } else if (!hasOutput) {
            status = ResultStatus.FAILURE;
        } else {
            status = ResultStatus.SUCCESS;
        }

        logger.info("Join '" + node.getId() + "' passthrough – " + status + " → " + writes);

        return NodeResult.builder()
                .status(status)
                .output(output)
                .metadata(
                        Map.of(
                                ForkNodeExecutor.JOIN_NODE_ID, node.getId(),
                                ForkNodeExecutor.MERGE_STRATEGY, node.getMergeStrategy().name()))
                .build();
    }
}
