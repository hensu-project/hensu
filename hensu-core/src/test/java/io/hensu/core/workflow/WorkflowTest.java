package io.hensu.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.node.ActionNode;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.NodeType;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkflowTest {

    @Nested
    class BuilderTest {

        @Test
        void shouldBuildWorkflowWithRequiredFields() {
            // Given
            Node startNode = createEndNode("start");

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getId()).isEqualTo("test-workflow");
            assertThat(workflow.getStartNode()).isEqualTo("start");
            assertThat(workflow.getNodes()).containsKey("start");
        }

        @Test
        void shouldBuildWorkflowWithVersion() {
            // Given
            Node startNode = createEndNode("start");

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("2.0.0")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getVersion()).isEqualTo("2.0.0");
        }

        @Test
        void shouldDefaultVersionTo1_0_0() {
            // Given
            Node startNode = createEndNode("start");

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getVersion()).isEqualTo("1.0.0");
        }

        @Test
        void shouldBuildWorkflowWithAgents() {
            // Given
            Node startNode = createEndNode("start");
            AgentConfig agentConfig = createAgentConfig("writer", "claude-sonnet-4");

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .agents(Map.of("writer", agentConfig))
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getAgents()).containsKey("writer");
            assertThat(workflow.getAgents().get("writer")).isEqualTo(agentConfig);
        }

        @Test
        void shouldBuildWorkflowWithRubrics() {
            // Given
            Node startNode = createEndNode("start");

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .rubrics(Map.of("quality", "rubric-content"))
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getRubrics()).containsEntry("quality", "rubric-content");
        }

        @Test
        void shouldBuildWorkflowWithConfig() {
            // Given
            Node startNode = createEndNode("start");
            WorkflowConfig config = new WorkflowConfig(5000L, true, 1000L, null);

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .config(config)
                            .build();

            // Then
            assertThat(workflow.getConfig()).isEqualTo(config);
        }

        @Test
        void shouldBuildWorkflowWithMetadata() {
            // Given
            Node startNode = createEndNode("start");
            WorkflowMetadata metadata =
                    new WorkflowMetadata(
                            "Test Workflow",
                            "A test workflow",
                            "Author",
                            Instant.now(),
                            List.of("test", "example"));

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .metadata(metadata)
                            .build();

            // Then
            assertThat(workflow.getMetadata()).isEqualTo(metadata);
        }

        @Test
        void shouldThrowWhenIdIsNull() {
            // Given
            Node startNode = createEndNode("start");

            // When/Then
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .nodes(Map.of("start", startNode))
                                            .startNode("start")
                                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Workflow ID required");
        }

        @Test
        void shouldThrowWhenStartNodeIsNull() {
            // Given
            Node startNode = createEndNode("start");

            // When/Then
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .id("test-workflow")
                                            .nodes(Map.of("start", startNode))
                                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Start node required");
        }

        @Test
        void shouldThrowWhenStartNodeNotInNodes() {
            // Given
            Node startNode = createEndNode("start");

            // When/Then
            assertThatThrownBy(
                            () ->
                                    Workflow.builder()
                                            .id("test-workflow")
                                            .nodes(Map.of("start", startNode))
                                            .startNode("nonexistent")
                                            .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Start node 'nonexistent' not found");
        }
    }

    @Nested
    class ImmutabilityTest {

        @Test
        void shouldMakeAgentsImmutable() {
            // Given
            Node startNode = createEndNode("start");
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getAgents()).isUnmodifiable();
        }

        @Test
        void shouldMakeRubricsImmutable() {
            // Given
            Node startNode = createEndNode("start");
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .rubrics(Map.of("quality", "content"))
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getRubrics()).isUnmodifiable();
        }

        @Test
        void shouldMakeNodesImmutable() {
            // Given
            Node startNode = createEndNode("start");
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getNodes()).isUnmodifiable();
        }
    }

    @Nested
    class EqualsAndHashCodeTest {

        @Test
        void shouldBeEqualWhenIdAndVersionMatch() {
            // Given
            Node startNode = createEndNode("start");

            Workflow workflow1 =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("1.0.0")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            Workflow workflow2 =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("1.0.0")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow1).isEqualTo(workflow2);
            assertThat(workflow1.hashCode()).isEqualTo(workflow2.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenIdDiffers() {
            // Given
            Node startNode = createEndNode("start");

            Workflow workflow1 =
                    Workflow.builder()
                            .id("workflow-1")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            Workflow workflow2 =
                    Workflow.builder()
                            .id("workflow-2")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow1).isNotEqualTo(workflow2);
        }

        @Test
        void shouldNotBeEqualWhenVersionDiffers() {
            // Given
            Node startNode = createEndNode("start");

            Workflow workflow1 =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("1.0.0")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            Workflow workflow2 =
                    Workflow.builder()
                            .id("test-workflow")
                            .version("2.0.0")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow1).isNotEqualTo(workflow2);
        }

        @Test
        void shouldBeEqualToItself() {
            // Given
            Node startNode = createEndNode("start");
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow).isEqualTo(workflow);
        }

        @Test
        void shouldNotBeEqualToNull() {
            // Given
            Node startNode = createEndNode("start");
            Workflow workflow =
                    Workflow.builder()
                            .id("test-workflow")
                            .nodes(Map.of("start", startNode))
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow).isNotEqualTo(null);
        }
    }

    @Test
    void shouldReturnMeaningfulToString() {
        // Given
        Node startNode = createEndNode("start");
        Workflow workflow =
                Workflow.builder()
                        .id("my-workflow")
                        .version("2.1.0")
                        .nodes(Map.of("start", startNode))
                        .startNode("start")
                        .build();

        // When
        String toString = workflow.toString();

        // Then
        assertThat(toString).contains("my-workflow");
        assertThat(toString).contains("2.1.0");
    }

    @Nested
    class ActionNodeTest {

        @Test
        void shouldBuildWorkflowWithActionNode() {
            // Given
            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "develop",
                    StandardNode.builder()
                            .id("develop")
                            .agentId("coder")
                            .prompt("Write code")
                            .transitionRules(List.of(new SuccessTransition("commit")))
                            .build());
            nodes.put(
                    "commit",
                    ActionNode.builder()
                            .id("commit")
                            .actions(List.of(new Action.Execute("git-commit")))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("action-workflow")
                            .nodes(nodes)
                            .startNode("develop")
                            .build();

            // Then
            assertThat(workflow.getNodes()).hasSize(3);
            assertThat(workflow.getNodes().get("commit")).isInstanceOf(ActionNode.class);

            ActionNode actionNode = (ActionNode) workflow.getNodes().get("commit");
            assertThat(actionNode.getNodeType()).isEqualTo(NodeType.ACTION);
            assertThat(actionNode.getActions()).hasSize(1);
        }

        @Test
        void shouldBuildWorkflowWithMultipleActionNodes() {
            // Given
            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "start",
                    StandardNode.builder()
                            .id("start")
                            .agentId("agent")
                            .prompt("Work")
                            .transitionRules(List.of(new SuccessTransition("commit")))
                            .build());
            nodes.put(
                    "commit",
                    ActionNode.builder()
                            .id("commit")
                            .actions(List.of(new Action.Execute("git-commit")))
                            .transitionRules(List.of(new SuccessTransition("notify")))
                            .build());
            nodes.put(
                    "notify",
                    ActionNode.builder()
                            .id("notify")
                            .actions(
                                    List.of(
                                            new Action.Notify("Build done", "slack"),
                                            new Action.HttpCall(
                                                    "https://webhook.example.com", "build-hook")))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("multi-action-workflow")
                            .nodes(nodes)
                            .startNode("start")
                            .build();

            // Then
            assertThat(workflow.getNodes()).hasSize(4);

            ActionNode commitNode = (ActionNode) workflow.getNodes().get("commit");
            assertThat(commitNode.getActions()).hasSize(1);

            ActionNode notifyNode = (ActionNode) workflow.getNodes().get("notify");
            assertThat(notifyNode.getActions()).hasSize(2);
        }

        @Test
        void shouldBuildWorkflowWithActionNodeAsStartNode() {
            // Given
            Map<String, Node> nodes = new HashMap<>();
            nodes.put(
                    "init",
                    ActionNode.builder()
                            .id("init")
                            .actions(List.of(new Action.Execute("setup-env")))
                            .transitionRules(List.of(new SuccessTransition("end")))
                            .build());
            nodes.put("end", EndNode.builder().id("end").status(ExitStatus.SUCCESS).build());

            // When
            Workflow workflow =
                    Workflow.builder()
                            .id("action-start-workflow")
                            .nodes(nodes)
                            .startNode("init")
                            .build();

            // Then
            assertThat(workflow.getStartNode()).isEqualTo("init");
            assertThat(workflow.getNodes().get("init")).isInstanceOf(ActionNode.class);
        }
    }

    private Node createEndNode(String id) {
        return EndNode.builder().id(id).status(ExitStatus.SUCCESS).build();
    }

    private AgentConfig createAgentConfig(String id, String model) {
        return AgentConfig.builder().id(id).role("assistant").model(model).build();
    }
}
