package io.hensu.core.execution.executor;

import io.hensu.core.execution.WorkflowExecutor;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowRepository;
import io.hensu.core.workflow.node.SubWorkflowNode;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/// Executes sub-workflow nodes by loading and running nested workflow definitions.
///
/// Resolves sub-workflows via the {@link WorkflowRepository} from the execution
/// context, maps parent state into the child context, executes the child workflow,
/// and maps outputs back to the parent state.
///
/// @implNote Reads tenant ID from `_tenant_id` in the execution state context
/// for tenant-scoped workflow lookup, and propagates it into the child so deeper
/// recursion stays under the same tenant. Enforces a static recursion-depth cap
/// as defence-in-depth — load-time cycle validation
/// ({@link io.hensu.core.workflow.validation.SubWorkflowGraphValidator}) covers
/// the normal entry paths, but in-memory workflows pushed straight into the
/// repository would bypass it.
///
/// @see WorkflowRepository for tenant-scoped workflow storage
public class SubWorkflowNodeExecutor implements NodeExecutor<SubWorkflowNode> {

    private static final Logger logger = Logger.getLogger(SubWorkflowNodeExecutor.class.getName());

    /// Hard cap on nested sub-workflow recursion. Cheap stack-depth guard for
    /// any workflow that bypasses the load-time cycle validator.
    public static final int MAX_DEPTH = 16;

    public static final String DEPTH_KEY = "_sub_workflow_depth";
    public static final String TENANT_KEY = "_tenant_id";

    @Override
    public Class<SubWorkflowNode> getNodeType() {
        return SubWorkflowNode.class;
    }

    @Override
    public NodeResult execute(SubWorkflowNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        WorkflowExecutor workflowExecutor = context.getWorkflowExecutor();

        logger.info("Executing sub-workflow: " + node.getWorkflowId());

        int parentDepth = readDepth(state.getContext());
        if (parentDepth >= MAX_DEPTH) {
            throw new IllegalStateException(
                    "Sub-workflow recursion depth exceeded "
                            + MAX_DEPTH
                            + " at: "
                            + node.getWorkflowId());
        }

        String tenantId = (String) state.getContext().get(TENANT_KEY);
        Workflow subWorkflow = loadSubWorkflow(node.getWorkflowId(), tenantId, context);

        Map<String, Object> subContext = new HashMap<>();
        for (Map.Entry<String, String> entry : node.getInputMapping().entrySet()) {
            String targetKey = entry.getKey();
            String sourceKey = entry.getValue();
            Object value = state.getContext().get(sourceKey);
            if (value == null) {
                throw new IllegalStateException("Missing input: " + sourceKey);
            }
            subContext.put(targetKey, value);
        }

        // Propagate engine vars so depth-2+ sub-workflows stay tenant-scoped
        // and continue to enforce the recursion cap.
        if (tenantId != null) {
            subContext.put(TENANT_KEY, tenantId);
        }
        subContext.put(DEPTH_KEY, parentDepth + 1);

        ExecutionResult subResult =
                workflowExecutor.execute(subWorkflow, subContext, context.getListener());

        if (subResult instanceof ExecutionResult.Completed completed) {
            for (Map.Entry<String, String> entry : node.getOutputMapping().entrySet()) {
                String targetKey = entry.getKey();
                String sourceKey = entry.getValue();
                state.getContext()
                        .put(targetKey, completed.getFinalState().getContext().get(sourceKey));
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sub_workflow", node.getWorkflowId());

            return new NodeResult(
                    ResultStatus.SUCCESS, completed.getFinalState().getContext(), metadata);
        }

        // Paused: child stopped for human review or async wait. Resume across the
        // sub-workflow boundary is not yet implemented — fail loudly instead of
        // silently masking the pause as a parent FAILURE.
        if (subResult instanceof ExecutionResult.Paused) {
            throw new UnsupportedOperationException(
                    "Sub-workflow '"
                            + node.getWorkflowId()
                            + "' paused; resume across sub-workflow boundary is not supported");
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sub_workflow", node.getWorkflowId());
        return new NodeResult(ResultStatus.FAILURE, "Sub-workflow failed", metadata);
    }

    private static int readDepth(Map<String, Object> ctx) {
        Object raw = ctx.get(DEPTH_KEY);
        return raw instanceof Integer i ? i : 0;
    }

    private Workflow loadSubWorkflow(String workflowId, String tenantId, ExecutionContext context) {
        WorkflowRepository repository = context.getWorkflowRepository();
        if (repository == null) {
            throw new IllegalStateException(
                    "No WorkflowRepository configured — cannot load sub-workflow: " + workflowId);
        }
        return repository
                .findById(tenantId != null ? tenantId : "default", workflowId)
                .orElseThrow(
                        () -> new IllegalStateException("Sub-workflow not found: " + workflowId));
    }
}
