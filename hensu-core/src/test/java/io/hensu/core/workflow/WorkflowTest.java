package io.hensu.core.workflow;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.workflow.node.Node;
import java.util.HashMap;
import java.util.Map;

public class WorkflowTest {

    // =========================================================================
    // Shared fixture — embedded so tests that need Workflow construction have
    // a zero-boilerplate API without a separate utility class.
    // Reference from other packages: WorkflowTest.TestWorkflowBuilder
    // =========================================================================

    public static final class TestWorkflowBuilder {

        public static final String RUBRIC_CONTENT =
                """
                # Rubric: quality
                ## Metadata
                - pass_threshold: 70
                ### Quality
                #### Structure
                - points: 10
                - evaluation: Is it organized
                """;

        private String id = "test-wf";
        private String startNodeId;
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<String, AgentConfig> agents = new HashMap<>();

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

        /// Fluent builder for workflows that need agents or custom id.
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

        public Workflow build() {
            return Workflow.builder()
                    .id(id)
                    .startNode(startNodeId)
                    .nodes(nodes)
                    .agents(agents)
                    .build();
        }
    }
}
