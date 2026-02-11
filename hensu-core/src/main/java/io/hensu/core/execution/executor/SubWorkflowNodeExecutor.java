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
/// for tenant-scoped workflow lookup.
///
/// @see WorkflowRepository for tenant-scoped workflow storage
public class SubWorkflowNodeExecutor implements NodeExecutor<SubWorkflowNode> {

    private static final Logger logger = Logger.getLogger(SubWorkflowNodeExecutor.class.getName());

    @Override
    public Class<SubWorkflowNode> getNodeType() {
        return SubWorkflowNode.class;
    }

    @Override
    public NodeResult execute(SubWorkflowNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        WorkflowExecutor workflowExecutor = context.getWorkflowExecutor();

        logger.info("Executing sub-workflow: " + node.getWorkflowId());

        // Load sub-workflow from repository using tenant ID from state context
        String tenantId = (String) state.getContext().get("_tenant_id");
        Workflow subWorkflow = loadSubWorkflow(node.getWorkflowId(), tenantId, context);

        // Map input context
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

        // Execute sub-workflow
        ExecutionResult subResult = workflowExecutor.execute(subWorkflow, subContext);

        // Map output back to parent state
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
        } else {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sub_workflow", node.getWorkflowId());

            return new NodeResult(ResultStatus.FAILURE, "Sub-workflow failed", metadata);
        }
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
