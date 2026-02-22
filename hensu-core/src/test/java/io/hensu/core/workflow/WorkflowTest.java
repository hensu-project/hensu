package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class WorkflowTest {

    // -------------------------------------------------------------------------
    // Builder — required fields, defaults, validation
    // -------------------------------------------------------------------------

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildWorkflowWithRequiredFields() {
            var workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", endNode("start")))
                            .startNode("start")
                            .build();

            assertThat(workflow.getId()).isEqualTo("test-workflow");
            assertThat(workflow.getStartNode()).isEqualTo("start");
            assertThat(workflow.getNodes()).containsKey("start");
            assertThat(workflow.getVersion()).isEqualTo("1.0.0"); // default
            assertThat(workflow.getAgents()).isEmpty();
            assertThat(workflow.getRubrics()).isEmpty();
        }

        @Test
        void shouldBuildWorkflowWithAllOptionalFields() {
            // All optional fields are preserved through the builder.
            // If a field is silently dropped this test fails.
            var agentConfig = agentConfig("writer", "claude-sonnet-4");
            var config = new WorkflowConfig(5000L, true, 1000L, null);

            var workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("2.0.0")
                            .agents(Map.of("writer", agentConfig))
                            .rubrics(Map.of("quality", "rubric-content"))
                            .nodes(Map.of("start", endNode("start")))
                            .startNode("start")
                            .config(config)
                            .build();

            assertThat(workflow.getVersion()).isEqualTo("2.0.0");
            assertThat(workflow.getAgents()).containsKey("writer");
            assertThat(workflow.getRubrics()).containsEntry("quality", "rubric-content");
            assertThat(workflow.getConfig()).isEqualTo(config);
        }

        @Test
        void shouldThrowWhenIdIsNull() {
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .nodes(Map.of("start", endNode("start")))
                                            .startNode("start")
                                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Workflow ID required");
        }

        @Test
        void shouldThrowWhenStartNodeIsNull() {
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .id("test-workflow")
                                            .nodes(Map.of("start", endNode("start")))
                                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Start node required");
        }

        @Test
        void shouldThrowWhenStartNodeNotInNodes() {
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .id("test-workflow")
                                            .nodes(Map.of("start", endNode("start")))
                                            .startNode("nonexistent")
                                            .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Start node 'nonexistent' not found");
        }

        @Test
        void shouldThrowWhenNodeReferencesUndeclaredRubric() {
            // Node declares rubricId="quality" but the workflow.rubrics map is empty.
            // The validate() method must detect this; otherwise a rubric lookup at
            // runtime silently fails or throws an unrelated NPE deep in the engine.
            var nodeWithRubric =
                    StandardNode.builder()
                            .id("start")
                            .rubricId("quality")
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build();

            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .id("test-workflow")
                                            .nodes(
                                                    Map.of(
                                                            "start",
                                                            nodeWithRubric,
                                                            "end",
                                                            endNode("end")))
                                            .startNode("start")
                                            .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("quality");
        }
    }

    // -------------------------------------------------------------------------
    // Immutability — all collections must be unmodifiable after build()
    // -------------------------------------------------------------------------

    @Nested
    class ImmutabilityTest {

        @Test
        void shouldMakeAllCollectionsImmutable() {
            var workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .agents(Map.of("writer", agentConfig("writer", "any")))
                            .rubrics(Map.of("quality", "content"))
                            .nodes(Map.of("start", endNode("start")))
                            .startNode("start")
                            .build();

            assertThat(workflow.getAgents()).isUnmodifiable();
            assertThat(workflow.getRubrics()).isUnmodifiable();
            assertThat(workflow.getNodes()).isUnmodifiable();
        }
    }

    // -------------------------------------------------------------------------
    // Equality — workflows are keyed by id + version
    // -------------------------------------------------------------------------

    @Nested
    class EqualsAndHashCodeTest {

        @Test
        void shouldBeEqualWhenIdAndVersionMatch() {
            var w1 = minimal("test-workflow", "1.0.0");
            var w2 = minimal("test-workflow", "1.0.0");

            assertThat(w1).isEqualTo(w2);
            assertThat(w1.hashCode()).isEqualTo(w2.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenIdDiffers() {
            assertThat(minimal("workflow-a", "1.0.0")).isNotEqualTo(minimal("workflow-b", "1.0.0"));
        }

        @Test
        void shouldNotBeEqualWhenVersionDiffers() {
            assertThat(minimal("test-workflow", "1.0.0"))
                    .isNotEqualTo(minimal("test-workflow", "2.0.0"));
        }
    }

    // -------------------------------------------------------------------------
    // TestWorkflowBuilder — shared fixture for processor and executor tests
    // -------------------------------------------------------------------------

    @Nested
    class TestWorkflowBuilderTest {

        @Test
        void singleNodeFactorySetsNodeAsStart() {
            var node = endNode("done");
            var workflow = TestWorkflowBuilder.singleNode(node);

            assertThat(workflow.getStartNode()).isEqualTo("done");
            assertThat(workflow.getNodes()).containsKey("done").hasSize(1);
        }

        @Test
        void withNodesFactorySetsFirstNodeAsStart() {
            var first = standardNode("step", "end");
            var last = endNode("end");

            var workflow = TestWorkflowBuilder.withNodes(first, last);

            assertThat(workflow.getStartNode()).isEqualTo("step");
            assertThat(workflow.getNodes()).hasSize(2);
        }

        @Test
        void fluentBuilderPreservesRubricAndAgentEntries() {
            var workflow =
                    TestWorkflowBuilder.create("wf")
                            .startNode(standardNode("step", "end"))
                            .node(endNode("end"))
                            .rubric("quality", "Be concise.")
                            .agent(agentConfig("writer", "claude-sonnet-4"))
                            .build();

            assertThat(workflow.getRubrics()).containsEntry("quality", "Be concise.");
            assertThat(workflow.getAgents()).containsKey("writer");
            assertThat(workflow.getStartNode()).isEqualTo("step");
        }
    }

    // =========================================================================
    // Shared fixture — embedded so tests that need Workflow construction have
    // a zero-boilerplate API without a separate utility class.
    // Reference from other packages: WorkflowTest.TestWorkflowBuilder
    // =========================================================================

    public static final class TestWorkflowBuilder {

        private String id = "test-wf";
        private String startNodeId;
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<String, AgentConfig> agents = new HashMap<>();
        private final Map<String, String> rubrics = new HashMap<>();

        private TestWorkflowBuilder() {}

        /// Single-node workflow. The node is its own start.
        public static Workflow singleNode(Node node) {
            return new TestWorkflowBuilder().startNode(node).build();
        }

        /// Linear workflow. First node becomes `startNode`; all nodes are registered. No
        /// transitions are wired — caller is responsible for building nodes with correct rules.
        public static Workflow withNodes(Node... nodes) {
            var b = new TestWorkflowBuilder();
            b.startNodeId = nodes[0].getId();
            for (var n : nodes) b.nodes.put(n.getId(), n);
            return b.build();
        }

        /// Fluent builder for workflows that need rubrics, agents, or custom id.
        public static TestWorkflowBuilder create(String id) {
            var b = new TestWorkflowBuilder();
            b.id = id;
            return b;
        }

        public TestWorkflowBuilder startNode(Node node) {
            this.startNodeId = node.getId();
            this.nodes.put(node.getId(), node);
            return this;
        }

        public TestWorkflowBuilder node(Node node) {
            this.nodes.put(node.getId(), node);
            return this;
        }

        public TestWorkflowBuilder agent(AgentConfig agent) {
            this.agents.put(agent.getId(), agent);
            return this;
        }

        public TestWorkflowBuilder rubric(String name, String content) {
            this.rubrics.put(name, content);
            return this;
        }

        public Workflow build() {
            return Workflow.builder()
                    .id(id)
                    .startNode(startNodeId)
                    .nodes(nodes)
                    .agents(agents)
                    .rubrics(rubrics)
                    .build();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Node endNode(String id) {
        return EndNode.builder().id(id).status(ExitStatus.SUCCESS).build();
    }

    private static Node standardNode(String id, String next) {
        return StandardNode.builder()
                .id(id)
                .transitionRules(List.of(new SuccessTransition(next)))
                .build();
    }

    private static AgentConfig agentConfig(String id, String model) {
        return AgentConfig.builder().id(id).role("assistant").model(model).build();
    }

    private static Workflow minimal(String id, String version) {
        return Workflow.builder()
                .id(id)
                .version(version)
                .nodes(Map.of("start", endNode("start")))
                .startNode("start")
                .build();
    }
}
