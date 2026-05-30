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
import io.hensu.core.workflow.node.SubWorkflowNode;
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
        assertThat(result).contains("agent-1");
    }

    @Test
    void shouldRenderTransitions() {
        Workflow workflow = createSimpleWorkflow("transition-workflow");

        String result = format.render(workflow, false);

        assertThat(result).contains("end");
        assertThat(result).contains("on success");
    }

    @Test
    void shouldRenderEndNodeWithExitStatus() {
        Workflow workflow = createSimpleWorkflow("end-workflow");

        String result = format.render(workflow, false);

        assertThat(result).contains("end");
        assertThat(result).contains("END");
        assertThat(result).contains("SUCCESS");
    }

    @Test
    void shouldRenderGenericNode() {
        Workflow workflow = createWorkflowWithGenericNode();

        String result = format.render(workflow, false);

        assertThat(result).contains("generic-step");
        assertThat(result).contains("GENERIC");
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
        assertThat(result).contains("retry");
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

    @Test
    void shouldRenderSubWorkflowNode() {
        Workflow workflow = createWorkflowWithSubWorkflow();

        String result = format.render(workflow, false);

        assertThat(result).contains("delegate");
        assertThat(result).contains("SUB_WORKFLOW");
        assertThat(result).contains("workflow");
        assertThat(result).contains("sub-summarizer");
        assertThat(result).contains("on success");
    }

    @Test
    void shouldRenderSubWorkflowNodeWithVersion() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "delegate",
                SubWorkflowNode.builder()
                        .id("delegate")
                        .workflowId("sub-summarizer")
                        .targetVersion("2.0.0")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow workflow =
                Workflow.builder()
                        .id("version-workflow")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "version-workflow",
                                        "Test",
                                        "tester",
                                        Instant.now(),
                                        List.of()))
                        .agents(Map.of())
                        .nodes(nodes)
                        .startNode("delegate")
                        .build();

        String result = format.render(workflow, false);

        assertThat(result).contains("version");
        assertThat(result).contains("2.0.0");
    }

    @Test
    void shouldInlineSubWorkflowWhenProvided() {
        Workflow root = createWorkflowWithSubWorkflow();
        Workflow sub = createSubWorkflow();

        String result = format.render(root, Map.of("sub-summarizer", sub), false);

        assertThat(result).contains("delegate");
        assertThat(result).contains("SUB_WORKFLOW");
        assertThat(result).contains("sub-start");
        assertThat(result).contains("STANDARD");
        assertThat(result).contains("summarizer-agent");

        var lines = result.lines().toList();
        int delegateLine = -1;
        int subStartLine = -1;
        int borderTopLine = -1;
        int borderBottomLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("delegate") && lines.get(i).contains("SUB_WORKFLOW")) {
                delegateLine = i;
            }
            if (lines.get(i).contains("sub-start") && lines.get(i).contains("STANDARD")) {
                subStartLine = i;
            }
            if (lines.get(i).contains("sub-summarizer") && lines.get(i).contains("┌")) {
                borderTopLine = i;
            }
            if (borderTopLine >= 0
                    && lines.get(i).contains("└")
                    && lines.get(i).contains("──────")) {
                borderBottomLine = i;
            }
        }
        assertThat(delegateLine)
                .as("delegate node must appear before inlined sub-start")
                .isGreaterThanOrEqualTo(0);
        assertThat(subStartLine)
                .as("sub-start must appear after delegate")
                .isGreaterThan(delegateLine);

        assertThat(borderTopLine)
                .as("bordered subgraph must have a top border with label")
                .isGreaterThan(delegateLine);
        assertThat(borderBottomLine)
                .as("bordered subgraph must have a bottom border")
                .isGreaterThan(subStartLine);
        assertThat(subStartLine)
                .as("sub-start must be inside the bordered subgraph")
                .isBetween(borderTopLine, borderBottomLine);
    }

    @Test
    void shouldHandleOverlappingNodeIds() {
        Map<String, Node> rootNodes = new HashMap<>();
        rootNodes.put(
                "start",
                SubWorkflowNode.builder()
                        .id("start")
                        .workflowId("child")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        rootNodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow root =
                Workflow.builder()
                        .id("parent")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "parent", "Parent", "tester", Instant.now(), List.of()))
                        .agents(Map.of())
                        .nodes(rootNodes)
                        .startNode("start")
                        .build();

        Map<String, Node> childNodes = new HashMap<>();
        childNodes.put(
                "start",
                StandardNode.builder()
                        .id("start")
                        .agentId("child-agent")
                        .prompt("Do child work")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        childNodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow child =
                Workflow.builder()
                        .id("child")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "child", "Child", "tester", Instant.now(), List.of()))
                        .agents(Map.of())
                        .nodes(childNodes)
                        .startNode("start")
                        .build();

        String result = format.render(root, Map.of("child", child), false);

        assertThat(result).contains("SUB_WORKFLOW");
        assertThat(result).contains("child-agent");
        long endCount = result.lines().filter(line -> line.contains("END")).count();
        assertThat(endCount).as("both parent and child 'end' nodes must render").isEqualTo(2);

        long startAsSubWorkflow =
                result.lines()
                        .filter(l -> l.contains("SUB_WORKFLOW") && l.contains("start"))
                        .count();
        long startAsStandard =
                result.lines().filter(l -> l.contains("STANDARD") && l.contains("start")).count();
        assertThat(startAsSubWorkflow).as("root 'start' renders as SUB_WORKFLOW").isEqualTo(1);
        assertThat(startAsStandard).as("child 'start' renders as STANDARD").isEqualTo(1);

        assertThat(result)
                .as("child workflow must be inside a bordered subgraph")
                .contains("child");
        long borderTopCount =
                result.lines()
                        .filter(l -> l.contains("┌") && l.contains("child") && l.contains("─"))
                        .count();
        assertThat(borderTopCount)
                .as("bordered subgraph top border present")
                .isGreaterThanOrEqualTo(1);
    }

    private Workflow createWorkflowWithSubWorkflow() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "delegate",
                SubWorkflowNode.builder()
                        .id("delegate")
                        .workflowId("sub-summarizer")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("sub-workflow-test")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "sub-workflow-test",
                                "Test workflow",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(Map.of())
                .nodes(nodes)
                .startNode("delegate")
                .build();
    }

    private Workflow createSubWorkflow() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "summarizer-agent",
                AgentConfig.builder()
                        .id("summarizer-agent")
                        .role("Summarizer")
                        .model("test-model")
                        .build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "sub-start",
                StandardNode.builder()
                        .id("sub-start")
                        .agentId("summarizer-agent")
                        .prompt("Summarize")
                        .transitionRules(List.of(new SuccessTransition("sub-end")))
                        .build());
        nodes.put("sub-end", EndNode.builder().id("sub-end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("sub-summarizer")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "sub-summarizer",
                                "Sub Summarizer",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(agents)
                .nodes(nodes)
                .startNode("sub-start")
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
