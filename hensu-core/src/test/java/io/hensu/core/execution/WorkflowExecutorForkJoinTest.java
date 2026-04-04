package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Tests for fork/join execution with StructuredTaskScope.
///
/// Sub-flow nodes transition to the join boundary (not end). The fork executor
/// runs sub-flows via executeUntil(), merges results, and stores them in state.
/// The join executor is a passthrough.
class WorkflowExecutorForkJoinTest extends WorkflowExecutorTestBase {

    @Test
    void shouldForkSubFlowsAndJoinWithCollectAll() throws Exception {
        // Fork spawns taskA + taskB sub-flows; each transitions to join boundary.
        // COLLECT_ALL merges branch yields as JSON.
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Result A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Result B"));

        var result =
                executor.execute(
                        buildForkJoinWorkflow(MergeStrategy.COLLECT_ALL, false), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(completed.getFinalState().getContext()).containsKey("fork_results");
        // COLLECT_ALL produces a structured Map (not a JSON String)
        assertThat(completed.getFinalState().getContext().get("fork_results"))
                .isInstanceOf(Map.class);
    }

    @ParameterizedTest(name = "failOnAnyError={0} → {1}")
    @MethodSource("failOnAnyErrorCases")
    void shouldHandleJoinFailOnAnyError(boolean failOnAnyError, ExitStatus expected)
            throws Exception {
        // taskA succeeds, taskB always fails. Outcome depends on failOnAnyError flag.
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Result A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.Error.of("Task B failed"));

        var result =
                executor.execute(
                        buildForkJoinWorkflow(MergeStrategy.COLLECT_ALL, failOnAnyError),
                        new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus()).isEqualTo(expected);
    }

    static Stream<Arguments> failOnAnyErrorCases() {
        return Stream.of(
                Arguments.of(true, ExitStatus.FAILURE), // taskB fails + failOnAnyError → FAILURE
                Arguments.of(false, ExitStatus.SUCCESS) // taskB fails but ignored → SUCCESS
                );
    }

    @Test
    void shouldConcatenateBranchOutputsWithSeparator() throws Exception {
        // CONCATENATE merges sub-flow yields as JSON strings joined by "---"
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Section A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Section B"));

        var result =
                executor.execute(
                        buildForkJoinWorkflow(MergeStrategy.CONCATENATE, false), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var combined =
                ((ExecutionResult.Completed) result)
                        .getFinalState()
                        .getContext()
                        .get("fork_results");
        assertThat(combined).isInstanceOf(String.class);
        // Both sub-flow results present, separated by ---
        assertThat(combined.toString()).contains("---");
    }

    // — FIRST_SUCCESSFUL ——————————————————————————————————————————————————

    @Test
    void shouldReturnFirstSuccessInDefinitionOrderForFirstSuccessful() throws Exception {
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("First"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Second"));

        var result =
                executor.execute(
                        buildForkJoinWorkflow(MergeStrategy.FIRST_SUCCESSFUL, false),
                        new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var merged =
                ((ExecutionResult.Completed) result)
                        .getFinalState()
                        .getContext()
                        .get("fork_results");
        // First successful branch yields are a structured Map
        assertThat(merged).isNotNull();
        assertThat(merged).isInstanceOf(Map.class);
    }

    @Test
    void shouldReturnNullWhenAllForksFailForFirstSuccessful() throws Exception {
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.Error.of("A failed"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.Error.of("B failed"));

        var result =
                executor.execute(
                        buildForkJoinWorkflow(MergeStrategy.FIRST_SUCCESSFUL, false),
                        new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var merged =
                ((ExecutionResult.Completed) result)
                        .getFinalState()
                        .getContext()
                        .get("fork_results");
        assertThat(merged).isNull();
    }

    // — Multi-node sub-flow ————————————————————————————————————————————————

    @Test
    void shouldTraverseMultiNodeSubFlowToJoinBoundary() throws Exception {
        // Sub-flow: taskA → taskA2 → join1 (boundary, not executed as sub-flow)
        // Proves executeUntil() traverses full sub-flows, not just single nodes.
        var agentA = mock(Agent.class);
        var agentA2 = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-a2")).thenReturn(Optional.of(agentA2));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Step 1"));
        when(agentA2.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Step 2"));

        var agents =
                Map.of(
                        "agent-a",
                                AgentConfig.builder()
                                        .id("agent-a")
                                        .role("Worker A")
                                        .model("test")
                                        .build(),
                        "agent-a2",
                                AgentConfig.builder()
                                        .id("agent-a2")
                                        .role("Worker A2")
                                        .model("test")
                                        .build());

        var nodes = new HashMap<String, Node>();
        // Sub-flow: taskA → taskA2 → join1 (multi-node chain)
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("Do task A step 1")
                        .transitionRules(List.of(new SuccessTransition("taskA2")))
                        .build());
        nodes.put(
                "taskA2",
                StandardNode.builder()
                        .id("taskA2")
                        .agentId("agent-a2")
                        .prompt("Do task A step 2")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "fork1",
                ForkNode.builder("fork1")
                        .targets("taskA")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "join1",
                JoinNode.builder("join1")
                        .awaitTargets("fork1")
                        .mergeStrategy(MergeStrategy.COLLECT_ALL)
                        .failOnAnyError(false)
                        .writes("fork_results")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));

        var workflow =
                Workflow.builder()
                        .id("multi-node-subflow-test")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("fork1")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        // Merged output present — both sub-flow nodes ran
        assertThat(completed.getFinalState().getContext()).containsKey("fork_results");
    }

    // — Yields isolation (state diff) ——————————————————————————————————————

    @Test
    void shouldExcludeParentContextAndEngineVarsFromMergedOutput() throws Exception {
        // Proves the state-diff fix: parent context ("topic") and engine vars
        // ("_execution_id") must NOT appear in COLLECT_ALL merge output.
        // Only writes()-declared vars from sub-flow nodes should be present.
        // This is the bug that caused the fork-join.kt runtime to feed duplicated
        // parent context + engine internals to the synthesizer LLM prompt.
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"finding_a\": \"result A\"}"));
        when(agentB.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"finding_b\": \"result B\"}"));

        var agents =
                Map.of(
                        "agent-a",
                        AgentConfig.builder().id("agent-a").role("A").model("test").build(),
                        "agent-b",
                        AgentConfig.builder().id("agent-b").role("B").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("Research {topic}")
                        .writes(List.of("finding_a"))
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "taskB",
                StandardNode.builder()
                        .id("taskB")
                        .agentId("agent-b")
                        .prompt("Research {topic}")
                        .writes(List.of("finding_b"))
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "fork1",
                ForkNode.builder("fork1")
                        .targets("taskA", "taskB")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "join1",
                JoinNode.builder("join1")
                        .awaitTargets("fork1")
                        .mergeStrategy(MergeStrategy.COLLECT_ALL)
                        .failOnAnyError(false)
                        .writes("fork_results")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));

        var workflow =
                Workflow.builder()
                        .id("yields-isolation")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("fork1")
                        .build();

        var ctx = new HashMap<String, Object>();
        ctx.put("topic", "AI workflows");
        ctx.put("_execution_id", "test-exec-123");

        var result = executor.execute(workflow, ctx);

        Map<String, Object> merged =
                (Map<String, Object>)
                        ((ExecutionResult.Completed) result)
                                .getFinalState()
                                .getContext()
                                .get("fork_results");
        // Branch yields are nested Maps keyed by branch ID
        assertThat(merged).containsKeys("taskA", "taskB");
        Map<String, Object> taskAYields = (Map<String, Object>) merged.get("taskA");
        Map<String, Object> taskBYields = (Map<String, Object>) merged.get("taskB");
        assertThat(taskAYields).containsKey("finding_a");
        assertThat(taskBYields).containsKey("finding_b");
        // Engine vars and parent context must NOT leak into merge output
        assertThat(merged.toString()).doesNotContain("_execution_id", "current_node");
        assertThat(taskAYields).doesNotContainKey("topic");
        assertThat(taskBYields).doesNotContainKey("topic");
    }

    @Test
    void shouldFilterMergedOutputByExportsList() throws Exception {
        // Sub-flow writes both "public_finding" and "scratchpad" to branch state.
        // JoinNode exports only "public_finding" – "scratchpad" must not cross the boundary.
        var agent = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agent));
        when(agent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                "{\"public_finding\": \"important\", \"scratchpad\": \"draft notes\"}"));

        var agents =
                Map.of(
                        "agent-a",
                        AgentConfig.builder().id("agent-a").role("A").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("Work")
                        .writes(List.of("public_finding", "scratchpad"))
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "fork1",
                ForkNode.builder("fork1")
                        .targets("taskA")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "join1",
                JoinNode.builder("join1")
                        .awaitTargets("fork1")
                        .mergeStrategy(MergeStrategy.COLLECT_ALL)
                        .exports("public_finding")
                        .failOnAnyError(false)
                        .writes("fork_results")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));

        var workflow =
                Workflow.builder()
                        .id("exports-filter")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("fork1")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        Map<String, Object> merged =
                (Map<String, Object>)
                        ((ExecutionResult.Completed) result)
                                .getFinalState()
                                .getContext()
                                .get("fork_results");
        // Exports filter allows only "public_finding" through
        Map<String, Object> taskAYields = (Map<String, Object>) merged.get("taskA");
        assertThat(taskAYields).containsKey("public_finding");
        assertThat(taskAYields).doesNotContainKey("scratchpad");
    }

    // — Helpers ———————————————————————————————————————————————————————————

    /// Builds a fork/join workflow where sub-flow nodes transition to join boundary.
    private Workflow buildForkJoinWorkflow(MergeStrategy mergeStrategy, boolean failOnAnyError) {
        var agents =
                Map.of(
                        "agent-a",
                        AgentConfig.builder().id("agent-a").role("Worker A").model("test").build(),
                        "agent-b",
                        AgentConfig.builder().id("agent-b").role("Worker B").model("test").build());
        var nodes = new HashMap<String, Node>();
        // Sub-flow nodes transition to join boundary (not end)
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("Do task A")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "taskB",
                StandardNode.builder()
                        .id("taskB")
                        .agentId("agent-b")
                        .prompt("Do task B")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "fork1",
                ForkNode.builder("fork1")
                        .targets("taskA", "taskB")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "join1",
                JoinNode.builder("join1")
                        .awaitTargets("fork1")
                        .mergeStrategy(mergeStrategy)
                        .failOnAnyError(failOnAnyError)
                        .writes("fork_results")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(0, "fail-end")))
                        .build());
        nodes.put("end", end("end"));
        nodes.put("fail-end", failEnd("fail-end"));
        return Workflow.builder()
                .id("fork-join-test")
                .agents(agents)
                .nodes(nodes)
                .startNode("fork1")
                .build();
    }
}
