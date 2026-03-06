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

class WorkflowExecutorForkJoinTest extends WorkflowExecutorTestBase {

    @Test
    void shouldForkTargetsAndJoinWithCollectAll() throws Exception {
        // Fork spawns taskA + taskB; join merges with COLLECT_ALL → result stored in context.
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Result A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Result B"));

        var result =
                executor.execute(buildForkJoinWorkflowCollectAllStrategy(false), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(completed.getFinalState().getContext()).containsKey("fork_results");
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
                        buildForkJoinWorkflowCollectAllStrategy(failOnAnyError), new HashMap<>());

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
        // CONCATENATE merges with "\n\n---\n\n". If someone changes the separator or
        // accidentally switches to COLLECT_ALL (which returns a Map), the contains()
        // assertions below fail — exposing the strategy mismatch.
        var agentA = mock(Agent.class);
        var agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Section A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Section B"));

        var result = executor.execute(buildForkJoinWorkflowConcatenateStrategy(), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var combined =
                ((ExecutionResult.Completed) result)
                        .getFinalState()
                        .getContext()
                        .get("fork_results");
        assertThat(combined).isInstanceOf(String.class);
        assertThat(combined.toString()).contains("Section A").contains("Section B");
    }

    // — Helpers ———————————————————————————————————————————————————————————

    private Workflow buildForkJoinWorkflowCollectAllStrategy(boolean failOnAnyError) {
        return buildForkJoinWorkflow(MergeStrategy.COLLECT_ALL, failOnAnyError);
    }

    private Workflow buildForkJoinWorkflowConcatenateStrategy() {
        return buildForkJoinWorkflow(MergeStrategy.CONCATENATE, false);
    }

    private Workflow buildForkJoinWorkflow(MergeStrategy mergeStrategy, boolean failOnAnyError) {
        var agents =
                Map.of(
                        "agent-a",
                        AgentConfig.builder().id("agent-a").role("Worker A").model("test").build(),
                        "agent-b",
                        AgentConfig.builder().id("agent-b").role("Worker B").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("Do task A")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put(
                "taskB",
                StandardNode.builder()
                        .id("taskB")
                        .agentId("agent-b")
                        .prompt("Do task B")
                        .transitionRules(List.of(new SuccessTransition("end")))
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
                        .outputField("fork_results")
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
