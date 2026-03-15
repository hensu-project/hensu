package io.hensu.cli.review;

import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;

/// Package-private utilities shared by review adapters.
final class ReviewUtils {

    private ReviewUtils() {}

    /// Resolves the prompt template for a given node in the workflow.
    ///
    /// @param workflow the workflow containing the node, not null
    /// @param nodeId   the node identifier to resolve, not null
    /// @return the prompt template string, or empty string if not available
    static String resolvePrompt(Workflow workflow, String nodeId) {
        Node node = workflow.getNodes().get(nodeId);
        if (node instanceof StandardNode sn && sn.getPrompt() != null) {
            return sn.getPrompt();
        }
        return "";
    }
}
