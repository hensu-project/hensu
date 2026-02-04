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

    private ByteArrayOutputStream outputStream;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream);
    }

    @Test
    void shouldPrintInputBlockOnAgentStart() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("test-node", "test-agent", "Test prompt content");

        String output = outputStream.toString();
        assertThat(output).contains("INPUT");
        assertThat(output).contains("test-node");
        assertThat(output).contains("test-agent");
        assertThat(output).contains("Test prompt content");
    }

    @Test
    void shouldPrintOutputBlockOnAgentComplete() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);
        AgentResponse response = AgentResponse.TextResponse.of("Agent output text");

        listener.onAgentComplete("test-node", "test-agent", response);

        String output = outputStream.toString();
        assertThat(output).contains("OUTPUT");
        assertThat(output).contains("test-node");
        assertThat(output).contains("test-agent");
        assertThat(output).contains("OK");
        assertThat(output).contains("Agent output text");
    }

    @Test
    void shouldPrintFailureStatusOnAgentComplete() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, true, null, null);
        AgentResponse response = AgentResponse.Error.of("Test error");

        listener.onAgentComplete("test-node", "test-agent", response);

        String output = outputStream.toString();
        assertThat(output).contains("OUTPUT");
        // Failure status is rendered using error color (red)
        assertThat(output).contains("\033[38;5;167m");
    }

    @Test
    void shouldUseBoxDrawingCharacters() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("┌─");
        assertThat(output).contains("└─");
    }

    @Test
    void shouldPrintMultilinePromptIndented() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "Line 1\nLine 2\nLine 3");

        String output = outputStream.toString();
        assertThat(output).contains("Line 1");
        assertThat(output).contains("Line 2");
        assertThat(output).contains("Line 3");
    }

    @Test
    void shouldPrintMultilineOutputIndented() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);
        AgentResponse response = AgentResponse.TextResponse.of("Output 1\nOutput 2\nOutput 3");

        listener.onAgentComplete("node", "agent", response);

        String output = outputStream.toString();
        assertThat(output).contains("Output 1");
        assertThat(output).contains("Output 2");
        assertThat(output).contains("Output 3");
    }

    @Test
    void shouldHandleEmptyPrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "");

        String output = outputStream.toString();
        assertThat(output).contains("(empty)");
    }

    @Test
    void shouldHandleNullPrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", null);

        String output = outputStream.toString();
        assertThat(output).contains("(empty)");
    }

    @Test
    void shouldHandleEmptyOutput() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);
        AgentResponse response = AgentResponse.TextResponse.of("");

        listener.onAgentComplete("node", "agent", response);

        String output = outputStream.toString();
        assertThat(output).contains("(empty)");
    }

    @Test
    void shouldApplyColorsWhenEnabled() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, true, null, null);

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("\033[");
    }

    @Test
    void shouldNotApplyColorsWhenDisabled() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).doesNotContain("\033[");
    }

    @Test
    void shouldRenderNodeVisualizationWhenWorkflowProvided() {
        Workflow workflow = createWorkflow();
        TextVisualizationFormat visualizer = new TextVisualizationFormat();
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, workflow, visualizer);

        listener.onAgentStart("start", "test-agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("start");
        assertThat(output).contains("STANDARD");
    }

    @Test
    void shouldSkipVisualizationWhenWorkflowNull() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(
                        printStream, false, null, new TextVisualizationFormat());

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("INPUT");
        assertThat(output).doesNotContain("STANDARD");
    }

    @Test
    void shouldSkipVisualizationWhenVisualizerNull() {
        Workflow workflow = createWorkflow();
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, workflow, null);

        listener.onAgentStart("start", "test-agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("INPUT");
    }

    @Test
    void shouldSkipVisualizationWhenNodeNotFound() {
        Workflow workflow = createWorkflow();
        TextVisualizationFormat visualizer = new TextVisualizationFormat();
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, workflow, visualizer);

        listener.onAgentStart("non-existent-node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("INPUT");
    }

    @Test
    void shouldPrintArrowBetweenNodeAndAgent() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("node");
        assertThat(output).contains("agent");
    }

    @Test
    void shouldPrintSeparatorLines() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "prompt");

        String output = outputStream.toString();
        assertThat(output).contains("─────");
    }

    @Test
    void shouldPrintBlankLineAfterBlocks() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "prompt");
        listener.onAgentComplete("node", "agent", AgentResponse.TextResponse.of("output"));

        String output = outputStream.toString();
        long blankLines = output.lines().filter(String::isBlank).count();
        assertThat(blankLines).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldHandleSpecialCharactersInPrompt() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);

        listener.onAgentStart("node", "agent", "Prompt with <tags> and {variables}");

        String output = outputStream.toString();
        assertThat(output).contains("<tags>");
        assertThat(output).contains("{variables}");
    }

    @Test
    void shouldHandleUnicodeInOutput() {
        VerboseExecutionListener listener =
                new VerboseExecutionListener(printStream, false, null, null);
        AgentResponse response = AgentResponse.TextResponse.of("Output with unicode: ✓ ✗ →");

        listener.onAgentComplete("node", "agent", response);

        String output = outputStream.toString();
        assertThat(output).contains("✓");
        assertThat(output).contains("✗");
        assertThat(output).contains("→");
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
