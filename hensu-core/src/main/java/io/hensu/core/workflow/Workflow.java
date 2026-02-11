package io.hensu.core.workflow;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.ParallelNode;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/// Immutable workflow definition representing a complete execution graph.
///
/// A workflow is a pure data structure containing agent configurations,
/// rubric mappings, node definitions, and execution metadata. Workflows
/// are constructed via the builder pattern and validated on build.
///
/// ### Structure
/// - **Agents**: Named agent configurations for node execution
/// - **Rubrics**: Mapping of rubric names to definition IDs
/// - **Nodes**: Graph of execution nodes with transitions
/// - **Config**: Execution behavior settings (timeouts, retries)
/// - **Metadata**: Descriptive information (author, description)
///
/// ### Validation
/// The builder validates that the start node exists in the node map.
/// Additional validation (reachability, cycle detection) is performed
/// by the workflow executor at runtime.
///
/// @implNote Immutable and thread-safe after construction. All collections
/// are wrapped in unmodifiable views.
///
/// @see io.hensu.core.execution.WorkflowExecutor for execution logic
/// @see Node for node type hierarchy
/// @see WorkflowConfig for execution settings
public final class Workflow {

    private final String id;
    private final String version;
    private final Map<String, AgentConfig> agents;
    private final Map<String, String> rubrics;
    private final Map<String, Node> nodes;
    private final String startNode;
    private final WorkflowConfig config;
    private final WorkflowMetadata metadata;

    private Workflow(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Workflow ID required");
        this.version = builder.version;
        this.agents = Collections.unmodifiableMap(builder.agents);
        this.rubrics = Collections.unmodifiableMap(builder.rubrics);
        this.nodes = Collections.unmodifiableMap(builder.nodes);
        this.startNode = Objects.requireNonNull(builder.startNode, "Start node required");
        this.config = builder.config;
        this.metadata = builder.metadata;

        validate();
    }

    private void validate() {
        if (!nodes.containsKey(startNode)) {
            throw new IllegalStateException(
                    "Start node '" + startNode + "' not found in workflow nodes");
        }

        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            String rubricId = entry.getValue().getRubricId();
            if (rubricId != null && !rubricId.isEmpty() && !rubrics.containsKey(rubricId)) {
                throw new IllegalStateException(
                        "Node '"
                                + entry.getKey()
                                + "' references rubric '"
                                + rubricId
                                + "' which is not declared in workflow rubrics");
            }

            if (entry.getValue() instanceof ParallelNode pn) {
                for (Branch branch : pn.getBranches()) {
                    if (branch.rubricId() != null
                            && !branch.rubricId().isEmpty()
                            && !rubrics.containsKey(branch.rubricId())) {
                        throw new IllegalStateException(
                                "Branch '"
                                        + branch.id()
                                        + "' in parallel node '"
                                        + entry.getKey()
                                        + "' references rubric '"
                                        + branch.rubricId()
                                        + "' which is not declared in workflow rubrics");
                    }
                }
            }
        }
    }

    /// Returns the unique workflow identifier.
    ///
    /// @return workflow ID, never null
    public String getId() {
        return id;
    }

    /// Returns the semantic version string.
    ///
    /// @return version in semver format (default "1.0.0"), never null
    public String getVersion() {
        return version;
    }

    /// Returns the agent configurations by name.
    ///
    /// @return unmodifiable map of agent name to config, never null
    public Map<String, AgentConfig> getAgents() {
        return agents;
    }

    /// Returns the rubric name to ID mappings.
    ///
    /// @return unmodifiable map of rubric name to definition ID, never null
    public Map<String, String> getRubrics() {
        return rubrics;
    }

    /// Returns all workflow nodes by ID.
    ///
    /// @return unmodifiable map of node ID to node definition, never null
    public Map<String, Node> getNodes() {
        return nodes;
    }

    /// Returns the entry point node ID.
    ///
    /// @return start node identifier, never null
    public String getStartNode() {
        return startNode;
    }

    /// Returns the workflow execution configuration.
    ///
    /// @return config settings, or null if using defaults
    public WorkflowConfig getConfig() {
        return config;
    }

    /// Returns the workflow metadata.
    ///
    /// @return metadata (author, description, etc.), or null if not set
    public WorkflowMetadata getMetadata() {
        return metadata;
    }

    /// Creates a new workflow builder.
    ///
    /// @return new builder instance, never null
    public static Builder builder() {
        return new Builder();
    }

    /// Builder for constructing immutable Workflow instances.
    ///
    /// Required fields: `id`, `startNode`
    /// The start node must exist in the nodes map.
    ///
    /// @see #build() for validation rules
    public static final class Builder {
        private String id;
        private String version = "1.0.0";
        private Map<String, AgentConfig> agents = Map.of();
        private Map<String, String> rubrics = Map.of();
        private Map<String, Node> nodes = Map.of();
        private String startNode;
        private WorkflowConfig config;
        private WorkflowMetadata metadata;

        private Builder() {}

        /// Sets the workflow identifier (required).
        ///
        /// @param id unique workflow ID, not null
        /// @return this builder for chaining
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /// Sets the semantic version string.
        ///
        /// @param version semver string (default "1.0.0"), not null
        /// @return this builder for chaining
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /// Sets the agent configurations.
        ///
        /// @param agents map of agent name to config, not null
        /// @return this builder for chaining
        public Builder agents(Map<String, AgentConfig> agents) {
            this.agents = Map.copyOf(agents);
            return this;
        }

        /// Sets the rubric name mappings.
        ///
        /// @param rubrics map of rubric name to definition ID, not null
        /// @return this builder for chaining
        public Builder rubrics(Map<String, String> rubrics) {
            this.rubrics = Map.copyOf(rubrics);
            return this;
        }

        /// Sets the workflow nodes.
        ///
        /// @param nodes map of node ID to node definition, not null
        /// @return this builder for chaining
        public Builder nodes(Map<String, Node> nodes) {
            this.nodes = Map.copyOf(nodes);
            return this;
        }

        /// Sets the entry point node ID (required).
        ///
        /// @param startNode ID of the first node to execute, not null
        /// @return this builder for chaining
        public Builder startNode(String startNode) {
            this.startNode = startNode;
            return this;
        }

        /// Sets the execution configuration.
        ///
        /// @param config workflow settings, may be null for defaults
        /// @return this builder for chaining
        public Builder config(WorkflowConfig config) {
            this.config = config;
            return this;
        }

        /// Sets the workflow metadata.
        ///
        /// @param metadata descriptive information, may be null
        /// @return this builder for chaining
        public Builder metadata(WorkflowMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /// Builds the immutable workflow instance.
        ///
        /// @return new Workflow instance, never null
        /// @throws NullPointerException if id or startNode is null
        /// @throws IllegalStateException if startNode not found in nodes
        public Workflow build() {
            return new Workflow(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Workflow workflow)) return false;
        return Objects.equals(id, workflow.id) && Objects.equals(version, workflow.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return "Workflow{id='" + id + "', version='" + version + "'}";
    }
}
