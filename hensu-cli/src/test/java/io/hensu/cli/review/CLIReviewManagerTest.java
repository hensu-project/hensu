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
import io.hensu.core.rubric.evaluator.RubricEvaluation;
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

class CLIReviewManagerTest {

    private ByteArrayOutputStream outputStream;
    private PrintStream printStream;
    private String originalInteractiveProperty;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream);
        originalInteractiveProperty = System.getProperty(CLIReviewManager.INTERACTIVE_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (originalInteractiveProperty != null) {
            System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, originalInteractiveProperty);
        } else {
            System.clearProperty(CLIReviewManager.INTERACTIVE_PROPERTY);
        }
    }

    @Test
    void shouldAutoApproveWhenInteractiveModeDisabled() {
        System.clearProperty(CLIReviewManager.INTERACTIVE_PROPERTY);
        Scanner scanner = new Scanner("");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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

    @Test
    void shouldApproveOnUserInputA() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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

    @Test
    void shouldApproveOnLowercaseInput() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("a\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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

    @Test
    void shouldRejectOnUserInputRWithReason() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("R\nOutput quality is poor\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Reject.class);
        ReviewDecision.Reject reject = (ReviewDecision.Reject) decision;
        assertThat(reject.getReason()).isEqualTo("Output quality is poor");
    }

    @Test
    void shouldRequireReasonForRejection() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("R\n\nActual reason\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        ReviewDecision decision =
                manager.requestReview(
                        createStandardNode("test-node"),
                        createSuccessResult(),
                        createState(),
                        new ExecutionHistory(),
                        new ReviewConfig(ReviewMode.REQUIRED, true, false),
                        createWorkflow());

        assertThat(decision).isInstanceOf(ReviewDecision.Reject.class);
        ReviewDecision.Reject reject = (ReviewDecision.Reject) decision;
        assertThat(reject.getReason()).isEqualTo("Actual reason");
        assertThat(outputStream.toString()).contains("Reason is required");
    }

    @Test
    void shouldDisplayReviewHeaderWithNodeInfo() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("my-test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("HUMAN REVIEW CHECKPOINT");
        assertThat(output).contains("my-test-node");
        assertThat(output).contains("SUCCESS");
    }

    @Test
    void shouldDisplayRubricScoreWhenAvailable() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        HensuState state = createState();
        state.setRubricEvaluation(
                RubricEvaluation.builder()
                        .score(85.0)
                        .passed(true)
                        .rubricId("quality-rubric")
                        .build());

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                state,
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("Rubric Score:");
        assertThat(output).contains("85");
        assertThat(output).contains("PASSED");
    }

    @Test
    void shouldDisplayFailedRubricScore() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        HensuState state = createState();
        state.setRubricEvaluation(
                RubricEvaluation.builder()
                        .score(45.0)
                        .passed(false)
                        .rubricId("quality-rubric")
                        .build());

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                state,
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("45");
        assertThat(output).contains("FAILED");
    }

    @Test
    void shouldDisplayOutputPreview() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        NodeResult result =
                NodeResult.builder()
                        .status(ResultStatus.SUCCESS)
                        .output("This is the agent output text")
                        .build();

        manager.requestReview(
                createStandardNode("test-node"),
                result,
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("Output Preview:");
        assertThat(output).contains("This is the agent output text");
    }

    @Test
    void shouldTruncateLongOutputPreview() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        String longOutput = "X".repeat(600);
        NodeResult result =
                NodeResult.builder().status(ResultStatus.SUCCESS).output(longOutput).build();

        manager.requestReview(
                createStandardNode("test-node"),
                result,
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("...");
        assertThat(output).doesNotContain("X".repeat(600));
    }

    @Test
    void shouldDisplayDetailedOutputOnV() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("V\n\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        NodeResult result =
                NodeResult.builder()
                        .status(ResultStatus.SUCCESS)
                        .output("Full detailed output here")
                        .metadata(Map.of("key1", "value1"))
                        .build();

        manager.requestReview(
                createStandardNode("test-node"),
                result,
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("DETAILED OUTPUT");
        assertThat(output).contains("Full detailed output here");
        assertThat(output).contains("Metadata:");
        assertThat(output).contains("key1");
    }

    @Test
    void shouldDisplayHistoryOnH() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("H\n\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-1")
                        .result(NodeResult.success("output1", Map.of()))
                        .timestamp(Instant.now())
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("step-2")
                        .result(NodeResult.success("output2", Map.of()))
                        .timestamp(Instant.now())
                        .build());

        manager.requestReview(
                createStandardNode("step-2"),
                createSuccessResult(),
                createState(),
                history,
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("EXECUTION HISTORY");
        assertThat(output).contains("step-1");
        assertThat(output).contains("step-2");
    }

    @Test
    void shouldDisplayHelpOnQuestionMark() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("?\n\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("REVIEW HELP");
        assertThat(output).contains("Commands:");
        assertThat(output).contains("Backtracking:");
        assertThat(output).contains("Prompt Editing:");
    }

    @Test
    void shouldWarnOnInvalidInput() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("X\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("Invalid option");
    }

    @Test
    void shouldShowBacktrackOptionWhenAllowed() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("[B]");
        assertThat(output).contains("Backtrack");
    }

    @Test
    void shouldHideBacktrackOptionWhenNotAllowed() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, false, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).doesNotContain("[B]");
    }

    @Test
    void shouldWarnWhenBacktrackNotAllowed() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        manager.requestReview(
                createStandardNode("test-node"),
                createSuccessResult(),
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, false, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("Backtracking is not allowed");
    }

    @Test
    void shouldWarnWhenNoPreviousStepsToBacktrack() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("only-step")
                        .result(NodeResult.success("output", Map.of()))
                        .build());

        manager.requestReview(
                createStandardNode("only-step"),
                createSuccessResult(),
                createState(),
                history,
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("No previous steps available");
    }

    @Test
    void shouldAllowBacktrackToPreviousStep() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\n1\nBad output\nN\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\n0\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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
    void shouldHandleInvalidBacktrackChoice() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\n99\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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

        manager.requestReview(
                createStandardNode("step-2"),
                createSuccessResult(),
                createState(),
                history,
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("Invalid choice");
    }

    @Test
    void shouldHandleNonNumericBacktrackInput() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\nabc\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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

        manager.requestReview(
                createStandardNode("step-2"),
                createSuccessResult(),
                createState(),
                history,
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("enter a valid number");
    }

    @Test
    void shouldUseDefaultReasonWhenBacktrackReasonBlank() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\n1\n\nN\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

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
        assertThat(backtrack.getReason()).isEqualTo("Manual backtrack by reviewer");
    }

    @Test
    void shouldDisplayBacktrackStepsWithStatus() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("B\n0\nA\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        ExecutionHistory history = new ExecutionHistory();
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("success-step")
                        .result(NodeResult.success("output1", Map.of()))
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("failure-step")
                        .result(NodeResult.failure("error"))
                        .build());
        history.addStep(
                ExecutionStep.builder()
                        .nodeId("current-step")
                        .result(NodeResult.success("output2", Map.of()))
                        .build());

        manager.requestReview(
                createStandardNode("current-step"),
                createSuccessResult(),
                createState(),
                history,
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("SELECT BACKTRACK TARGET");
        assertThat(output).contains("success-step");
        assertThat(output).contains("failure-step");
        assertThat(output).contains("OK");
        assertThat(output).contains("FAIL");
    }

    @Test
    void shouldDisplayNoOutputWhenNull() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        NodeResult result = NodeResult.builder().status(ResultStatus.SUCCESS).output(null).build();

        manager.requestReview(
                createStandardNode("test-node"),
                result,
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("(no output)");
    }

    @Test
    void shouldFormatFailureStatus() {
        System.setProperty(CLIReviewManager.INTERACTIVE_PROPERTY, "true");
        Scanner scanner = new Scanner("A\n");
        CLIReviewManager manager = new CLIReviewManager(scanner, printStream, false);

        NodeResult result =
                NodeResult.builder().status(ResultStatus.FAILURE).output("error message").build();

        manager.requestReview(
                createStandardNode("test-node"),
                result,
                createState(),
                new ExecutionHistory(),
                new ReviewConfig(ReviewMode.REQUIRED, true, false),
                createWorkflow());

        String output = outputStream.toString();
        assertThat(output).contains("FAILURE");
    }

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
