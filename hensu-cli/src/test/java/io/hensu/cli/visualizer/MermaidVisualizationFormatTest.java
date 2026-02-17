package io.hensu.cli.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.LoopNode;
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

class MermaidVisualizationFormatTest {

    private MermaidVisualizationFormat format;

    @BeforeEach
    void setUp() {
        format = new MermaidVisualizationFormat();
    }

    @Test
    void shouldReturnMermaidAsFormatName() {
        assertThat(format.getName()).isEqualTo("mermaid");
    }

    @Test
    void shouldWrapOutputInCodeBlock() {
        Workflow workflow = createSimpleWorkflow("test-workflow");

        String result = format.render(workflow);

        assertThat(result).startsWith("```mermaid\n");
        assertThat(result).endsWith("```\n");
    }

    @Test
    void shouldUseFlowchartTopDown() {
        Workflow workflow = createSimpleWorkflow("flowchart-workflow");

        String result = format.render(workflow);

        assertThat(result).contains("flowchart TD");
    }

    @Test
    void shouldRenderSubgraphWithWorkflowName() {
        Workflow workflow = createSimpleWorkflow("subgraph-workflow");

        String result = format.render(workflow);

        assertThat(result).contains("subgraph");
        assertThat(result).contains("subgraph-workflow");
    }

    @Test
    void shouldRenderStandardNodeAsRectangle() {
        Workflow workflow = createSimpleWorkflow("standard-workflow");

        String result = format.render(workflow);

        // Standard nodes use rectangle syntax: id["label"]
        assertThat(result).contains("start[\"");
    }

    @Test
    void shouldRenderEndNodeAsStadium() {
        Workflow workflow = createSimpleWorkflow("end-workflow");

        String result = format.render(workflow);

        // End nodes use stadium syntax: id(["label"])
        assertThat(result).contains("node_end([\"");
        assertThat(result).contains("SUCCESS");
    }

    @Test
    void shouldRenderSuccessTransitionAsSolidArrow() {
        Workflow workflow = createSimpleWorkflow("success-transition");

        String result = format.render(workflow);

        // Success transitions use solid arrow: -->
        assertThat(result).contains("start --> ");
    }

    @Test
    void shouldRenderFailureTransitionAsDashedArrow() {
        Workflow workflow = createWorkflowWithFailureTransition();

        String result = format.render(workflow);

        // Failure transitions use dashed arrow with label: -.->|label|
        assertThat(result).contains("-.->|");
    }

    @Test
    void shouldRenderRetryCountInFailureLabel() {
        Workflow workflow = createWorkflowWithFailureTransition();

        String result = format.render(workflow);

        assertThat(result).contains("retry 3");
    }

    @Test
    void shouldRenderGenericNodeAsHexagon() {
        Workflow workflow = createWorkflowWithGenericNode();

        String result = format.render(workflow);

        // Generic nodes use hexagon syntax: id{{"label"}}
        assertThat(result).contains("{{\"");
        assertThat(result).contains("validator");
    }

    @Test
    void shouldRenderLoopNodeAsDiamond() {
        Workflow workflow = createWorkflowWithLoopNode();

        String result = format.render(workflow);

        // Loop nodes use diamond syntax: id{"label"}
        assertThat(result).contains("{\"");
        assertThat(result).contains("loop");
    }

    @Test
    void shouldSanitizeReservedKeywords() {
        Workflow workflow = createWorkflowWithReservedKeyword();

        String result = format.render(workflow);

        // Reserved keywords should be prefixed with "node_"
        assertThat(result).contains("node_end");
    }

    @Test
    void shouldSanitizeSpecialCharacters() {
        Map<String, AgentConfig> agents = new HashMap<>();
        agents.put(
                "agent-1", AgentConfig.builder().id("agent-1").role("Test").model("test").build());

        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "node-with-dash",
                StandardNode.builder()
                        .id("node-with-dash")
                        .agentId("agent-1")
                        .prompt("Test")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow workflow =
                Workflow.builder()
                        .id("special-chars")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "special", "Test", "t", Instant.now(), List.of()))
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("node-with-dash")
                        .build();

        String result = format.render(workflow);

        // Dashes should be replaced with underscores
        assertThat(result).contains("node_with_dash");
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

    private Workflow createWorkflowWithGenericNode() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "generic",
                GenericNode.builder()
                        .id("generic")
                        .executorType("validator")
                        .config(Map.of("field", "input"))
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
                .agents(Map.of())
                .nodes(nodes)
                .startNode("generic")
                .build();
    }

    private Workflow createWorkflowWithLoopNode() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put("loop", new LoopNode("loop"));
        nodes.put("exit", EndNode.builder().id("exit").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("loop-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "loop-workflow",
                                "Test workflow",
                                "tester",
                                Instant.now(),
                                List.of()))
                .agents(Map.of())
                .nodes(nodes)
                .startNode("loop")
                .build();
    }

    private Workflow createWorkflowWithReservedKeyword() {
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
        // "end" is a reserved Mermaid keyword
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        return Workflow.builder()
                .id("reserved-workflow")
                .version("1.0.0")
                .metadata(
                        new WorkflowMetadata(
                                "reserved-workflow",
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
