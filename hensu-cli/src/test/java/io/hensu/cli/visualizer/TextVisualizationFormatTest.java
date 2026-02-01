package io.hensu.cli.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextVisualizationFormatTest {

    private TextVisualizationFormat format;

    @BeforeEach
    void setUp() {
        format = new TextVisualizationFormat();
    }

    @Test
    void shouldReturnTextAsFormatName() {
        assertThat(format.getName()).isEqualTo("text");
    }

    @Test
    void shouldRenderWorkflowHeader() {
        Workflow workflow = createSimpleWorkflow("test-workflow");

        String result = format.render(workflow);

        assertThat(result).contains("Workflow:");
        assertThat(result).contains("test-workflow");
    }

    @Test
    void shouldRenderStandardNodeWithAgent() {
        Workflow workflow = createSimpleWorkflow("agent-workflow");

        String result = format.render(workflow, false);

        assertThat(result).contains("start");
        assertThat(result).contains("STANDARD");
        assertThat(result).contains("Agent:");
        assertThat(result).contains("agent-1");
    }

    @Test
    void shouldRenderTransitions() {
        Workflow workflow = createSimpleWorkflow("transition-workflow");

        String result = format.render(workflow, false);

        assertThat(result).contains("Transitions:");
        assertThat(result).contains("end");
        assertThat(result).contains("on success");
    }

    @Test
    void shouldRenderEndNodeWithExitStatus() {
        Workflow workflow = createSimpleWorkflow("end-workflow");

        String result = format.render(workflow, false);

        assertThat(result).contains("end");
        assertThat(result).contains("END");
        assertThat(result).contains("Exit:");
        assertThat(result).contains("SUCCESS");
    }

    @Test
    void shouldRenderGenericNode() {
        Workflow workflow = createWorkflowWithGenericNode();

        String result = format.render(workflow, false);

        assertThat(result).contains("generic-step");
        assertThat(result).contains("GENERIC");
        assertThat(result).contains("Executor:");
        assertThat(result).contains("validator");
    }

    @Test
    void shouldRenderWithColors() {
        Workflow workflow = createSimpleWorkflow("color-workflow");

        String result = format.render(workflow, true);

        // Should contain ANSI escape codes
        assertThat(result).contains("\033[");
    }

    @Test
    void shouldRenderWithoutColors() {
        Workflow workflow = createSimpleWorkflow("plain-workflow");

        String result = format.render(workflow, false);

        // Should not contain ANSI escape codes
        assertThat(result).doesNotContain("\033[");
    }

    @Test
    void shouldRenderFailureTransitions() {
        Workflow workflow = createWorkflowWithFailureTransition();

        String result = format.render(workflow, false);

        assertThat(result).contains("on failure");
        assertThat(result).contains("retry:");
    }

    @Test
    void shouldRenderSingleNode() {
        StandardNode node =
                StandardNode.builder()
                        .id("test-node")
                        .agentId("agent-1")
                        .prompt("Test prompt")
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        String result = format.renderNode(node, "test-node", false);

        assertThat(result).contains("test-node");
        assertThat(result).contains("STANDARD");
        assertThat(result).contains("agent-1");
    }

    @Test
    void shouldUseBoxDrawingCharacters() {
        StandardNode node =
                StandardNode.builder()
                        .id("box-node")
                        .agentId("agent-1")
                        .prompt("Test")
                        .transitionRules(List.of())
                        .build();

        String result = format.renderNode(node, "box-node", false);

        assertThat(result).contains("┌─");
        assertThat(result).contains("│");
        assertThat(result).contains("└─");
    }

    private Workflow createSimpleWorkflow(String name) {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "agent-1",
                AgentConfig.builder().id("agent-1").role("Test Agent").model("test-model").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("agent-1")
                        .prompt("Test prompt")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id(name)
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                name, "Test workflow", "tester", Instant.now(), List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("start")
                .build();
    }

    private Workflow createWorkflowWithGenericNode() {
        Map<String, AgentConfig> agents = new HashMap<>();

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "generic-step",
                GenericNode.builder()
                        .id("generic-step")
                        .executorType("validator")
                        .config(Map.of("field", "input", "required", true))
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("generic-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "generic-workflow",
                                "Test workflow",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("generic-step")
                .build();
    }

    private Workflow createWorkflowWithFailureTransition() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "agent-1",
                AgentConfig.builder().id("agent-1").role("Test Agent").model("test-model").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("agent-1")
                        .prompt("Test prompt")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(3, "error")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());
        nodes.put("error", EndNode.builder().id("error").status(ExitStatus.FAILURE).build());

        return Workflow.builder()
                .id("failure-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "failure-workflow",
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
