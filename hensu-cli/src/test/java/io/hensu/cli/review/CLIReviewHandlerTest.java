package io.hensu.cli.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionStep;
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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CLIReviewHandlerTest {

    private PrintStream printStream;
    private String originalInteractiveProperty;

    @BeforeEach
    void setUp() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream);
        originalInteractiveProperty = System.getProperty(CLIReviewHandler.INTERACTIVE_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (originalInteractiveProperty != null) {
            System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, originalInteractiveProperty);
        } else {
            System.clearProperty(CLIReviewHandler.INTERACTIVE_PROPERTY);
        }
    }

    // — Non-interactive ——————————————————————————————————————————————————————

    @Test
    void shouldAutoApproveWhenInteractiveModeDisabled() {
        System.clearProperty(CLIReviewHandler.INTERACTIVE_PROPERTY);
        CLIReviewHandler manager = new CLIReviewHandler(new Scanner(""), printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    // — Rejection —————————————————————————————————————————————————————————————

    @Test
    void shouldRejectOnUserInputRWithReason() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        CLIReviewHandler manager =
                new CLIReviewHandler(
                        new Scanner("R\nOutput quality is poor\n"), printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Reject.class);
        assertThat(((ReviewDecision.Reject) decision).getReason())
                .isEqualTo("Output quality is poor");
    }

    @Test
    void shouldRequireNonBlankReasonForRejection() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        // First reason attempt is blank, second is valid
        CLIReviewHandler manager =
                new CLIReviewHandler(new Scanner("R\n\nActual reason\n"), printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Reject.class);
        assertThat(((ReviewDecision.Reject) decision).getReason()).isEqualTo("Actual reason");
    }

    // — Backtrack guard conditions ————————————————————————————————————————————

    @Test
    void shouldContinueWhenBacktrackDisabled() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        // B pressed but backtrack not allowed — expect the loop to continue and A to approve
        CLIReviewHandler manager = new CLIReviewHandler(new Scanner("B\nA\n"), printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, false, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    @Test
    void shouldContinueWhenNoPreviousStepsToBacktrackTo() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        // Only one step in history (the current node itself) — nothing to go back to
        CLIReviewHandler manager = new CLIReviewHandler(new Scanner("B\nA\n"), printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("only-step")
                        .result(NodeResult.success("output", Map.of()))
                        .build());

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("only-step"),
                        createSuccessResult(),
                        createState(),
                        history,
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    // — Backtrack flow ————————————————————————————————————————————————————————

    @Test
    void shouldAllowBacktrackToPreviousStep() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        CLIReviewHandler manager =
                new CLIReviewHandler(new Scanner("B\n1\nBad output\nN\n"), printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-1")
                        .result(NodeResult.success("output1", Map.of()))
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-2")
                        .result(NodeResult.success("output2", Map.of()))
                        .build());

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("step-2"),
                        createSuccessResult(),
                        createState(),
                        history,
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Backtrack.class);
        ReviewDecision.Backtrack backtrack = (ReviewDecision.Backtrack) decision;
        assertThat(backtrack.getTargetStep()).isEqualTo("step-1");
        assertThat(backtrack.getReason()).isEqualTo("Bad output");
    }

    @Test
    void shouldCancelBacktrackOnZero() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        CLIReviewHandler manager =
                new CLIReviewHandler(new Scanner("B\n0\nA\n"), printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-1")
                        .result(NodeResult.success("output1", Map.of()))
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-2")
                        .result(NodeResult.success("output2", Map.of()))
                        .build());

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("step-2"),
                        createSuccessResult(),
                        createState(),
                        history,
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Approve.class);
    }

    @Test
    void shouldUseDefaultReasonWhenBacktrackReasonBlank() {
        System.setProperty(CLIReviewHandler.INTERACTIVE_PROPERTY, "true");
        CLIReviewHandler manager =
                new CLIReviewHandler(new Scanner("B\n1\n\nN\n"), printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-1")
                        .result(NodeResult.success("output1", Map.of()))
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-2")
                        .result(NodeResult.success("output2", Map.of()))
                        .build());

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("step-2"),
                        createSuccessResult(),
                        createState(),
                        history,
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Backtrack.class);
        assertThat(((ReviewDecision.Backtrack) decision).getReason())
                .isEqualTo("Manual backtrack by reviewer");
    }

    // — Helpers ———————————————————————————————————————————————————————————————

    private StandardNode createStandardNode(String id) {
        return StandardNode.builder()
                .id(id)
                .agentId("test-agent")
                .prompt("Test prompt")
                .transitionRules(List.of(new SuccessTransition("end")))
                .build();
    }

    private NodeResult createSuccessResult() {
        return NodeResult.builder()
                .status(ResultStatus.SUCCESS)
                .output("Test output")
                .metadata(Map.of())
                .build();
    }

    private HensuState createState() {
        return new HensuState(new HashMap<>(), "workflow-1", "test-node", new ExecutionHistory());
    }

    private Workflow createWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "test-agent",
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test Agent")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Test prompt")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("test-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "test-workflow",
                                "Test workflow",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }
}
