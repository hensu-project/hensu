package io.hensu.server.review;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewOutcome;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InteractiveReviewHandler implements ReviewHandler {
    private static final Logger LOG = Logger.getLogger(InteractiveReviewHandler.class);

    @Override
    public ReviewOutcome requestReview(
            Node node,
            NodeResult result,
            HensuState state,
            ExecutionHistory history,
            ReviewConfig config,
            Workflow workflow) {
        String correlationId = UUID.randomUUID().toString();
        LOG.infov(
                "Interactive review requested for node: {0}, correlationId: {1}",
                node.getId(), correlationId);
        return ReviewOutcome.pending(correlationId);
    }
}
