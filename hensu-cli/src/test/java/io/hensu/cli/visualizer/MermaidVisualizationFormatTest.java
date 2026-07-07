package io.hensu.cli.visualizer;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowMetadata;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.GenericNode;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.ParallelNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.node.SubWorkflowNode;
import io.hensu.core.workflow.transition.BoundedTransition;
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
    void shouldRenderSubgraphWithWorkflowName() {
        Workflow workflow = createSimpleWorkflow("subgraph-workflow");

        String result = format.render(workflow);

        assertThat(result).contains("subgraph");
        assertThat(result).contains("subgraph-workflow");
    }

    @Test
    void shouldRenderStandardNodeAsStadium() {
        Workflow workflow = createSimpleWorkflow("standard-workflow");

        String result = format.render(workflow);

        // Standard nodes use stadium (pill) syntax: id(["label"])
        assertThat(result).contains("start([\"");
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

        assertThat(result).contains("retry ≤3");
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
                                        new BoundedTransition(
                                                new FailureTransition(null),
                                                "failure",
                                                3,
                                                "error")))
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

    @Test
    void shouldRenderSubWorkflowNodeAsCylinder() {
        Workflow workflow = createWorkflowWithSubWorkflow();

        String result = format.render(workflow);

        assertThat(result).contains("[(\"");
        assertThat(result).contains("sub: sub-summarizer");
    }

    @Test
    void shouldRenderSubWorkflowEdges() {
        Workflow workflow = createWorkflowWithSubWorkflow();

        String result = format.render(workflow);

        assertThat(result).contains("delegate --> node_end");
    }

    @Test
    void shouldInlineSubWorkflowAsNestedSubgraph() {
        Workflow root = createWorkflowWithSubWorkflow();
        Workflow sub = createSubWorkflow();

        String result = format.render(root, Map.of("sub-summarizer", sub));

        assertThat(result).contains("subgraph sub_summarizer[\"sub-summarizer\"]");
        assertThat(result).contains("sub_summarizer__sub_start");
        assertThat(result).contains("-->|sub|");
        assertThat(result).contains("sub_summarizer__sub_end");
        assertThat(result).contains("delegate -->|sub| sub_summarizer__sub_start");
        assertThat(result).contains("sub_summarizer__sub_start --> sub_summarizer__sub_end");
    }

    @Test
    void shouldNamespaceOverlappingNodeIds() {
        Map<String, Node> rootNodes = new HashMap<>();
        rootNodes.put(
                "init",
                SubWorkflowNode.builder()
                        .id("init")
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
                        .startNode("init")
                        .build();

        Map<String, Node> childNodes = new HashMap<>();
        childNodes.put(
                "init",
                StandardNode.builder()
                        .id("init")
                        .agentId("agent-1")
                        .prompt("Work")
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
                        .startNode("init")
                        .build();

        String result = format.render(root, Map.of("child", child));

        assertThat(result).contains("child__init");
        assertThat(result).contains("child__node_end");
        assertThat(result).contains("init([");
        assertThat(result).contains("init -->|sub| child__init");
        assertThat(result).contains("child__init --> child__node_end");

        long initOccurrences =
                result.lines()
                        .filter(l -> l.trim().startsWith("init([") || l.trim().startsWith("init[("))
                        .count();
        assertThat(initOccurrences).as("root 'init' node defined exactly once").isEqualTo(1);
    }

    @Test
    void shouldEmitDarkModeStyleDeclarations() {
        Workflow workflow = createSimpleWorkflow("styled-workflow");

        String result = format.render(workflow);

        assertThat(result).contains("style styled_workflow fill:#2c2c2e, stroke:#3a3a3c");
        assertThat(result).contains("style start fill:#2c2c2e, stroke:#48484a");
        assertThat(result).contains("style node_end fill:#2c2c2e, stroke:#48484a");
        assertThat(result).contains("linkStyle default stroke:#0A84FF, stroke-width:1px");
    }

    @Test
    void shouldStyleNestedSubgraphDifferently() {
        Workflow root = createWorkflowWithSubWorkflow();
        Workflow sub = createSubWorkflow();

        String result = format.render(root, Map.of("sub-summarizer", sub));

        assertThat(result)
                .as("nested subgraph uses surface-nested fill")
                .contains("style sub_summarizer fill:#3a3a3c, stroke:#48484a");
        assertThat(result)
                .as("root subgraph uses surface fill")
                .contains("style sub_workflow_test fill:#2c2c2e, stroke:#3a3a3c");
    }

    @Test
    void shouldEscapeQuotesInLabels() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "step",
                StandardNode.builder()
                        .id("step")
                        .agentId("agent-1")
                        .prompt("Test")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow workflow =
                Workflow.builder()
                        .id("quote-test")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "has \"quotes\"",
                                        "Test",
                                        "tester",
                                        Instant.now(),
                                        List.of()))
                        .agents(Map.of())
                        .nodes(nodes)
                        .startNode("step")
                        .build();

        String result = format.render(workflow);

        assertThat(result)
                .as("quotes in workflow name must be escaped")
                .contains("has &quot;quotes&quot;");
        assertThat(result)
                .as("no unescaped quotes breaking Mermaid syntax")
                .doesNotContain("[\"has \"quotes\"");
    }

    @Test
    void shouldRenderForkJoinShapes() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "split",
                ForkNode.builder("split")
                        .targets("branch-a", "branch-b")
                        .transitionRules(List.of(new SuccessTransition("merge")))
                        .build());
        nodes.put(
                "branch-a",
                StandardNode.builder()
                        .id("branch-a")
                        .agentId("worker")
                        .prompt("A")
                        .transitionRules(List.of())
                        .build());
        nodes.put(
                "branch-b",
                StandardNode.builder()
                        .id("branch-b")
                        .agentId("worker")
                        .prompt("B")
                        .transitionRules(List.of())
                        .build());
        nodes.put(
                "merge",
                JoinNode.builder("merge")
                        .awaitTargets("branch-a", "branch-b")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow workflow =
                Workflow.builder()
                        .id("fork-join-test")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "fork-join-test",
                                        "Test",
                                        "tester",
                                        Instant.now(),
                                        List.of()))
                        .agents(Map.of())
                        .nodes(nodes)
                        .startNode("split")
                        .build();

        String result = format.render(workflow);

        assertThat(result).as("fork uses asymmetric shape").contains("split>\"");
        assertThat(result).as("join uses plain rectangle").contains("merge[\"");
        assertThat(result).as("fork edge label").contains("-->|fork|");
        assertThat(result).as("join await edge").contains("-.->|await|");
        assertThat(result).as("fork targets rendered").contains("branch_a").contains("branch_b");
    }

    @Test
    void shouldDecomposeParallelNodeIntoBranchAndJoinNodes() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "proposals",
                ParallelNode.builder("proposals")
                        .branch("innovative", "proposer1", "Innovate")
                        .branch("practical", "proposer2", "Practical")
                        .branch("safe", "proposer3", "Safe")
                        .consensus("judge", ConsensusStrategy.JUDGE_DECIDES)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("refine"),
                                        new FailureTransition("rejected")))
                        .build());
        nodes.put(
                "refine",
                StandardNode.builder()
                        .id("refine")
                        .agentId("refiner")
                        .prompt("Refine")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("rejected", EndNode.builder().id("rejected").status(ExitStatus.FAILURE).build());
        nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

        Workflow workflow =
                Workflow.builder()
                        .id("parallel-test")
                        .version("1.0.0")
                        .metadata(
                                new WorkflowMetadata(
                                        "parallel-test",
                                        "Test",
                                        "tester",
                                        Instant.now(),
                                        List.of()))
                        .agents(Map.of())
                        .nodes(nodes)
                        .startNode("proposals")
                        .build();

        String result = format.render(workflow);

        assertThat(result)
                .as("parallel uses asymmetric shape with branch count")
                .contains("proposals>\"proposals\\n(parallel: 3)\"]");
        assertThat(result)
                .as("branch nodes rendered as stadiums with agent")
                .contains("proposals_innovative([\"innovative\\n[proposer1]\"])")
                .contains("proposals_practical([\"practical\\n[proposer2]\"])")
                .contains("proposals_safe([\"safe\\n[proposer3]\"])");
        assertThat(result)
                .as("synthetic join node with consensus strategy")
                .contains("proposals___join[\"proposals\\n(join: JUDGE_DECIDES)\"]");
        assertThat(result)
                .as("fan-out edges from parallel to branches")
                .contains("proposals -->|branch| proposals_innovative")
                .contains("proposals -->|branch| proposals_practical")
                .contains("proposals -->|branch| proposals_safe");
        assertThat(result)
                .as("branch-to-join edges")
                .contains("proposals_innovative --> proposals___join")
                .contains("proposals_practical --> proposals___join")
                .contains("proposals_safe --> proposals___join");
        assertThat(result)
                .as("transitions from join node")
                .contains("proposals___join --> refine")
                .contains("proposals___join -.->|failure| rejected");
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
