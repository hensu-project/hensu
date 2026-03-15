package io.hensu.cli.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.daemon.DaemonFrame;
import io.hensu.cli.daemon.ExecutionStatus;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DaemonReviewHandlerTest {

    private DaemonReviewHandler handler;
    private BlockingQueue<DaemonFrame> sentFrames;
    private List<ExecutionStatus> statusUpdates;

    @BeforeEach
    void setUp() {
        handler = new DaemonReviewHandler();
        sentFrames = new ArrayBlockingQueue<>(10);
        statusUpdates = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        handler.unregisterExecution("exec-1");
    }

    // — Normal round-trip ————————————————————————————————————————————————————

    @Test
    void shouldRouteReviewThroughDaemonWhenRegistered() throws Exception {
        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);

        CompletableFuture<ReviewDecision> decisionFuture =
                CompletableFuture.supplyAsync(
                        () ->
                                handler.requestReview(
                                        createNode(),
                                        createResult(),
                                        createState(),
                                        new ExecutionHistory(),
                                        createConfig(),
                                        createWorkflow()),
                        Thread::startVirtualThread);

        DaemonFrame reviewFrame = sentFrames.poll(5, TimeUnit.SECONDS);
        assertThat(reviewFrame).isNotNull();
        assertThat(reviewFrame.type).isEqualTo("review_request");
        assertThat(reviewFrame.reviewId).isNotNull();
        assertThat(statusUpdates).contains(ExecutionStatus.AWAITING_REVIEW);

        handler.completeReview(reviewFrame.reviewId, new ReviewDecision.Approve(null));

        ReviewDecision decision = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
        assertThat(statusUpdates).contains(ExecutionStatus.RUNNING);
    }

    @Test
    void shouldCompleteReviewWithBacktrackDecision() throws Exception {
        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);

        CompletableFuture<ReviewDecision> decisionFuture =
                CompletableFuture.supplyAsync(
                        () ->
                                handler.requestReview(
                                        createNode(),
                                        createResult(),
                                        createState(),
                                        new ExecutionHistory(),
                                        createConfig(),
                                        createWorkflow()),
                        Thread::startVirtualThread);

        DaemonFrame reviewFrame = sentFrames.poll(5, TimeUnit.SECONDS);
        assertThat(reviewFrame).isNotNull();

        handler.completeReview(
                reviewFrame.reviewId, new ReviewDecision.Backtrack("step-1", "Bad output"));

        ReviewDecision decision = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ReviewDecision.Backtrack.class);
        assertThat(((ReviewDecision.Backtrack) decision).getTargetStep()).isEqualTo("step-1");
    }

    // — Suspend / Resume ——————————————————————————————————————————————————————

    @Test
    void shouldRedeliverReviewRequestOnResume() throws Exception {
        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);

        CompletableFuture<ReviewDecision> decisionFuture =
                CompletableFuture.supplyAsync(
                        () ->
                                handler.requestReview(
                                        createNode(),
                                        createResult(),
                                        createState(),
                                        new ExecutionHistory(),
                                        createConfig(),
                                        createWorkflow()),
                        Thread::startVirtualThread);

        DaemonFrame reviewFrame = sentFrames.poll(5, TimeUnit.SECONDS);
        assertThat(reviewFrame).isNotNull();
        String reviewId = reviewFrame.reviewId;

        // Client disconnects
        handler.suspendExecution("exec-1");

        // New client attaches — pending review_request should be re-delivered
        BlockingQueue<DaemonFrame> newClientFrames = new ArrayBlockingQueue<>(10);
        handler.resumeExecution("exec-1", newClientFrames::add);

        DaemonFrame redelivered = newClientFrames.poll(5, TimeUnit.SECONDS);
        assertThat(redelivered).isNotNull();
        assertThat(redelivered.type).isEqualTo("review_request");
        assertThat(redelivered.reviewId).isEqualTo(reviewId);

        // Complete via the original reviewId
        handler.completeReview(reviewId, new ReviewDecision.Approve(null));
        ReviewDecision decision = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    // — Cancel pending reviews ————————————————————————————————————————————————

    @Test
    void shouldAutoApprovePendingReviewsOnUnregister() throws Exception {
        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);

        CompletableFuture<ReviewDecision> decisionFuture =
                CompletableFuture.supplyAsync(
                        () ->
                                handler.requestReview(
                                        createNode(),
                                        createResult(),
                                        createState(),
                                        new ExecutionHistory(),
                                        createConfig(),
                                        createWorkflow()),
                        Thread::startVirtualThread);

        DaemonFrame reviewFrame = sentFrames.poll(5, TimeUnit.SECONDS);
        assertThat(reviewFrame).isNotNull();

        // Unregister cancels pending reviews with auto-approve
        handler.unregisterExecution("exec-1");

        ReviewDecision decision = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    // — Interactive state ——————————————————————————————————————————————————————

    @Test
    void shouldTrackInteractiveStateAcrossLifecycle() {
        assertThat(handler.isInteractive("exec-1")).isFalse();

        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);
        assertThat(handler.isInteractive("exec-1")).isTrue();

        handler.suspendExecution("exec-1");
        assertThat(handler.isInteractive("exec-1")).isTrue();

        handler.unregisterExecution("exec-1");
        assertThat(handler.isInteractive("exec-1")).isFalse();
    }

    // — Late response ————————————————————————————————————————————————————————

    @Test
    void shouldIgnoreLateReviewResponse() {
        handler.registerExecution("exec-1", sentFrames::add, statusUpdates::add);

        handler.completeReview("nonexistent-review-id", new ReviewDecision.Reject("late"));

        // No pending review was affected — sentFrames stays empty, no status change
        assertThat(sentFrames).isEmpty();
        assertThat(statusUpdates).isEmpty();
    }

    // — Helpers ———————————————————————————————————————————————————————————————

    private StandardNode createNode() {
        return StandardNode.builder()
                .id("node-1")
                .agentId("agent-1")
                .prompt("Test prompt")
                .transitionRules(List.of(new SuccessTransition("end")))
                .build();
    }

    private NodeResult createResult() {
        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output("Test output")
                .metadata(Map.of())
                .build();
    }

    private HensuState createState() {
        return new HensuState.Builder()
                .executionId("exec-1")
                .workflowId("wf-1")
                .currentNode("node-1")
                .context(new HashMap<>())
                .history(new ExecutionHistory())
                .build();
    }

    private ReviewConfig createConfig() {
        return new ReviewConfig(ReviewMode.REQUIRED, true, false);
    }

    private Workflow createWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put("agent-1", AgentConfig.builder().id("agent-1").role("R").model("M").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put("node-1", createNode());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("wf-1")
                .version("1.0")
                .metadata(new WorkflowMetadata("wf-1", "Test", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("node-1")
                .build();
    }
}
