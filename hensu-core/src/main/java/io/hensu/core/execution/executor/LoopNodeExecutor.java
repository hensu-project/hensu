package io.hensu.core.execution.executor;

import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.node.LoopNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.BreakRule;
import io.hensu.core.workflow.transition.LoopCondition;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/// Executes loop nodes by iterating over a body node until conditions are met.
///
/// ### This executor
///
/// - Iterates up to maxIterations times
/// - Evaluates loop conditions before each iteration
/// - Checks break rules after each iteration
/// - Delegates body execution to the appropriate executor via registry
public class LoopNodeExecutor implements NodeExecutor<LoopNode> {

    private static final Logger logger = Logger.getLogger(LoopNodeExecutor.class.getName());

    @Override
    public Class<LoopNode> getNodeType() {
        return LoopNode.class;
    }

    @Override
    public NodeResult execute(LoopNode node, ExecutionContext context) throws Exception {
        HensuState state = context.getState();
        NodeExecutorRegistry registry = context.getNodeExecutorRegistry();

        int iteration = 0;
        NodeResult lastResult = null;

        logger.info(
                "Entering loop: " + node.getId() + ", max iterations: " + node.getMaxIterations());

        while (iteration < node.getMaxIterations()
                && evaluateLoopCondition(node.getCondition(), state)) {

            logger.info(
                    "Loop "
                            + node.getId()
                            + " iteration "
                            + (iteration + 1)
                            + "/"
                            + node.getMaxIterations());

            // Execute loop body using the registry (decoupled from specific executor)
            if (node.getBodyNode() == null) {
                throw new IllegalStateException(
                        "Loop node '" + node.getId() + "' has no body node defined");
            }
            if (node.getBodyNode() instanceof StandardNode bodyNode) {
                NodeExecutor<StandardNode> bodyExecutor =
                        registry.getExecutorOrThrow(StandardNode.class);
                lastResult = bodyExecutor.execute(bodyNode, context);
            } else {
                throw new IllegalStateException(
                        "Unsupported body node type: " + node.getBodyNode().getClass());
            }

            // Update state with result
            state.getContext().put("loop_iteration", iteration);
            state.getContext().put("loop_last_result", lastResult);

            // Check break conditions
            if (node.getBreakRules() != null) {
                for (BreakRule breakRule : node.getBreakRules()) {
                    if (evaluateLoopCondition(breakRule.getCondition(), state)) {
                        logger.info(
                                "Loop break condition met, jumping to: "
                                        + breakRule.getTargetNode());
                        state.setLoopBreakTarget(breakRule.getTargetNode());
                        return lastResult;
                    }
                }
            }

            iteration++;
        }

        logger.info("Loop " + node.getId() + " completed after " + iteration + " iterations");

        return lastResult != null ? lastResult : NodeResult.empty();
    }

    private boolean evaluateLoopCondition(LoopCondition condition, HensuState state) {
        if (condition instanceof LoopCondition.Always) {
            return true;
        } else if (condition instanceof LoopCondition.Expression expr) {
            return evaluateExpression(expr.getExpr(), state.getContext());
        }
        return false;
    }

    private boolean evaluateExpression(String expr, Map<String, Object> context) {
        if (expr.contains("<")) {
            String[] parts = expr.split("<");
            Object left = resolveValue(parts[0].trim(), context);
            Object right = resolveValue(parts[1].trim(), context);
            if (left instanceof Number && right instanceof Number) {
                return ((Number) left).doubleValue() < ((Number) right).doubleValue();
            }
            return false;
        } else if (expr.contains(">")) {
            String[] parts = expr.split(">");
            Object left = resolveValue(parts[0].trim(), context);
            Object right = resolveValue(parts[1].trim(), context);
            if (left instanceof Number && right instanceof Number) {
                return ((Number) left).doubleValue() > ((Number) right).doubleValue();
            }
            return false;
        } else if (expr.contains("==")) {
            String[] parts = expr.split("==");
            Object left = resolveValue(parts[0].trim(), context);
            Object right = resolveValue(parts[1].trim(), context);
            return Objects.equals(left, right);
        } else {
            Object value = resolveValue(expr, context);
            return value instanceof Boolean ? (Boolean) value : false;
        }
    }

    private Object resolveValue(String key, Map<String, Object> context) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(key);
            } catch (NumberFormatException e2) {
                if ("true".equals(key)) return true;
                if ("false".equals(key)) return false;
                return context.get(key);
            }
        }
    }
}
