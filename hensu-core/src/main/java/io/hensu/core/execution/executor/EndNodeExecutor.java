package io.hensu.core.execution.executor;

import io.hensu.core.workflow.node.EndNode;

/// Executes end nodes.
///
/// This executor is stateless. Terminal logging is handled centrally by
/// `io.hensu.core.execution.WorkflowExecutor#logTerminalResult`.
///
/// ### Features
///
/// - Returns a proper end result (never null)
public class EndNodeExecutor implements NodeExecutor<EndNode> {

    @Override
    public Class<EndNode> getNodeType() {
        return EndNode.class;
    }

    @Override
    public NodeResult execute(EndNode node, ExecutionContext context) {
        return NodeResult.end();
    }
}
