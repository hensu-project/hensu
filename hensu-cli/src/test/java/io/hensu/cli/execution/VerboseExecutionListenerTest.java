package io.hensu.cli.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.visualizer.TextVisualizationFormat;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExitStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerboseExecutionListenerTest {

    private final int termWidth = 80;
    private ByteArrayOutputStream outputStream;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream);
    }

    // — Agent start/complete ——————————————————————————————————————————————————

    @Test
    void shouldPrintInputBlockOnAgentStart() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentStart("test-node", "test-agent", "Test prompt content");

        String output = outputStream.toString();
        assertThat(output).contains("input");
        assertThat(output).contains("test-node");
        assertThat(output).contains("test-agent");
        assertThat(output).contains("Test prompt content");
    }

    @Test
    void shouldPrintOutputBlockOnAgentComplete() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentComplete(
                "test-node", "test-agent", AgentResponse.TextResponse.of("Agent output text"));

        String output = outputStream.toString();
        assertThat(output).contains("output");
        assertThat(output).contains("test-node");
        assertThat(output).contains("test-agent");
        assertThat(output).contains("OK");
        assertThat(output).contains("Agent output text");
    }

    @Test
    void shouldShowErrorStatusOnAgentFailure() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, true, null, null, termWidth);

        listener.onAgentComplete("test-node", "test-agent", AgentResponse.Error.of("Test error"));

        String output = outputStream.toString();
        assertThat(output).contains("ERROR");
        assertThat(output).contains("\033["); // error is styled when color is enabled
    }

    // — Edge cases ————————————————————————————————————————————————————————————

    @Test
    void shouldHandleNullPrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentStart("node", "agent", null);

        assertThat(outputStream.toString()).contains("(empty)");
    }

    @Test
    void shouldHandleEmptyPrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentStart("node", "agent", "");

        assertThat(outputStream.toString()).contains("(empty)");
    }

    @Test
    void shouldHandleEmptyOutput() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentComplete("node", "agent", AgentResponse.TextResponse.of(""));

        assertThat(outputStream.toString()).contains("(empty)");
    }

    @Test
    void shouldHandleMultilinePrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentStart("node", "agent", "Line 1\nLine 2\nLine 3");

        String output = outputStream.toString();
        assertThat(output).contains("Line 1");
        assertThat(output).contains("Line 2");
        assertThat(output).contains("Line 3");
    }

    // — Color feature flag ————————————————————————————————————————————————————

    @Test
    void shouldApplyColorsWhenEnabled() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, true, null, null, termWidth);

        listener.onAgentStart("node", "agent", "prompt");

        assertThat(outputStream.toString()).contains("\033[");
    }

    @Test
    void shouldNotApplyColorsWhenDisabled() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null, termWidth);

        listener.onAgentStart("node", "agent", "prompt");

        assertThat(outputStream.toString()).doesNotContain("\033[");
    }

    // — Node visualization guard conditions ——————————————————————————————————

    @Test
    void shouldRenderNodeVisualizationWhenWorkflowProvided() {
        Workflow workflow = createWorkflow();
        VerboseExecutionListener listener =
                new VerboseExecutionListener(
                        printStream, false, workflow, new TextVisualizationFormat(), termWidth);

        listener.onAgentStart("start", "test-agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("start");
        assertThat(output).contains("STANDARD");
    }

    @Test
    void shouldSkipVisualizationWhenWorkflowNull() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(
                        printStream, false, null, new TextVisualizationFormat(), termWidth);

        listener.onAgentStart("node", "agent", "prompt");

        assertThat(outputStream.toString()).contains("input");
        assertThat(outputStream.toString()).doesNotContain("STANDARD");
    }

    @Test
    void shouldSkipVisualizationWhenVisualizerNull() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, createWorkflow(), null, termWidth);

        listener.onAgentStart("start", "test-agent", "prompt");

        assertThat(outputStream.toString()).contains("input");
    }

    @Test
    void shouldSkipVisualizationWhenNodeNotFound() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(
                        printStream,
                        false,
                        createWorkflow(),
                        new TextVisualizationFormat(),
                        termWidth);

        listener.onAgentStart("non-existent-node", "agent", "prompt");

        assertThat(outputStream.toString()).contains("input");
    }

    // — Helpers ———————————————————————————————————————————————————————————————

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
