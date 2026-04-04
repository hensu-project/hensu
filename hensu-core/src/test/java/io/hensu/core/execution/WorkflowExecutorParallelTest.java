package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.parallel.Branch;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.ParallelNode;
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

class WorkflowExecutorParallelTest extends WorkflowExecutorTestBase {

    // — Structured consensus pipeline ————————————————————————————————————

    @ParameterizedTest
    @MethodSource("majorityConsensusCases")
    void shouldRouteByMajorityVote(String r1, String r2, String r3, ExitStatus expected)
            throws Exception {
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        var agent3 = mock(Agent.class);
        when(agentRegistry.getAgent("reviewer1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("reviewer2")).thenReturn(Optional.of(agent2));
        when(agentRegistry.getAgent("reviewer3")).thenReturn(Optional.of(agent3));
        when(agent1.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r1));
        when(agent2.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r2));
        when(agent3.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of(r3));

        var result = executor.execute(buildMajorityWorkflow(), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus()).isEqualTo(expected);
    }

    static Stream<Arguments> majorityConsensusCases() {
        return Stream.of(
                // 2 approve / 1 reject → majority (2 > 1.5) → SUCCESS
                Arguments.of(
                        """
                        {"score": 90, "approved": true, "recommendation": "Good"}""",
                        """
                        {"score": 85, "approved": true, "recommendation": "Solid"}""",
                        """
                        {"score": 30, "approved": false, "recommendation": "Needs work"}""",
                        ExitStatus.SUCCESS),
                // 1 approve / 2 reject → majority rejects → FAILURE
                Arguments.of(
                        """
                        {"score": 90, "approved": true, "recommendation": "Good"}""",
                        """
                        {"score": 20, "approved": false, "recommendation": "Bad"}""",
                        """
                        {"score": 15, "approved": false, "recommendation": "Bad"}""",
                        ExitStatus.FAILURE));
    }

    // — Yields merge: the core new feature ———————————————————————————————

    @Test
    void shouldMergeWinnerYieldsIntoContext() throws Exception {
        // Both branches approve (unanimous) and yield "api_schema".
        // After consensus, the winning branch's extracted api_schema must be in context.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("a1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("a2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 90, "approved": true, "recommendation": "Good",\
                 "api_schema": "openapi: 3.0"}"""));
        when(agent2.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 85, "approved": true, "recommendation": "Solid",\
                 "api_schema": "openapi: 3.1"}"""));

        var agents =
                Map.of(
                        "a1", AgentConfig.builder().id("a1").role("Agent").model("test").build(),
                        "a2", AgentConfig.builder().id("a2").role("Agent").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch(new Branch("b1", "a1", "Analyze", null, 1.0, List.of("api_schema")))
                        .branch(new Branch("b2", "a2", "Review", null, 1.0, List.of("api_schema")))
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow =
                Workflow.builder()
                        .id("yields-merge")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(completed.getFinalState().getContext()).containsKey("api_schema");
        assertThat(completed.getFinalState().getContext().get("api_schema").toString())
                .startsWith("openapi:");
    }

    @Test
    void shouldMergeAllBranchYieldsForVoteStrategies() throws Exception {
        // b1+b3 approve → consensus reached, b2 rejects.
        // Vote strategies merge ALL yields – the vote gates the transition,
        // not the data. Every branch's domain output flows to downstream nodes.
        // 3 branches needed: majority requires >50% (strict), so 2/3 > 1.5 passes.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        var agent3 = mock(Agent.class);
        when(agentRegistry.getAgent("a1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("a2")).thenReturn(Optional.of(agent2));
        when(agentRegistry.getAgent("a3")).thenReturn(Optional.of(agent3));
        when(agent1.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 90, "approved": true, "recommendation": "Good",\
                 "winner_data": "from-b1"}"""));
        when(agent2.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 20, "approved": false, "recommendation": "Bad",\
                 "loser_data": "from-b2"}"""));
        when(agent3.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 80, "approved": true, "recommendation": "Solid"}"""));

        var agents =
                Map.of(
                        "a1", AgentConfig.builder().id("a1").role("Agent").model("test").build(),
                        "a2", AgentConfig.builder().id("a2").role("Agent").model("test").build(),
                        "a3", AgentConfig.builder().id("a3").role("Agent").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch(new Branch("b1", "a1", "Work", null, 1.0, List.of("winner_data")))
                        .branch(new Branch("b2", "a2", "Work", null, 1.0, List.of("loser_data")))
                        .branch(new Branch("b3", "a3", "Work", null, 1.0, List.of()))
                        .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(0, "fail-end")))
                        .build());
        nodes.put("end", end("end"));
        nodes.put("fail-end", failEnd("fail-end"));
        var workflow =
                Workflow.builder()
                        .id("loser-isolation")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        assertThat(completed.getFinalState().getContext()).containsEntry("winner_data", "from-b1");
        assertThat(completed.getFinalState().getContext()).containsEntry("loser_data", "from-b2");
    }

    // — Branch isolation ————————————————————————————————————————————————————

    @Test
    void shouldIsolateBranchContextFromSiblings() throws Exception {
        // Both branches read a shared "input" key from parent context.
        // Each produces a different yield. If isolation breaks, one branch
        // could see the other's extracted values during execution.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("a1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("a2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 90, "approved": true, "recommendation": "Good",\
                 "branch1_out": "from-branch-1"}"""));
        when(agent2.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 85, "approved": true, "recommendation": "OK",\
                 "branch2_out": "from-branch-2"}"""));

        var agents =
                Map.of(
                        "a1", AgentConfig.builder().id("a1").role("Agent").model("test").build(),
                        "a2", AgentConfig.builder().id("a2").role("Agent").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch(
                                new Branch(
                                        "b1",
                                        "a1",
                                        "Work on {input}",
                                        null,
                                        1.0,
                                        List.of("branch1_out")))
                        .branch(
                                new Branch(
                                        "b2",
                                        "a2",
                                        "Work on {input}",
                                        null,
                                        1.0,
                                        List.of("branch2_out")))
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow =
                Workflow.builder()
                        .id("isolation-test")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var initialContext = new HashMap<String, Object>();
        initialContext.put("input", "shared-data");

        var result = executor.execute(workflow, initialContext);

        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        // Both winners' yields merged
        assertThat(completed.getFinalState().getContext())
                .containsEntry("branch1_out", "from-branch-1")
                .containsEntry("branch2_out", "from-branch-2");
        // Parent input survived untouched
        assertThat(completed.getFinalState().getContext()).containsEntry("input", "shared-data");
    }

    // — Branch crash under StructuredTaskScope ————————————————————————————

    @Test
    void shouldProduceFailureWhenBranchThrowsRuntimeException() throws Exception {
        // If an agent throws a RuntimeException mid-flight, the branch must catch it
        // and return a FAILURE BranchResult — consistent with ForkNodeExecutor's
        // sub-flow error handling. The consensus evaluator then sees the failure and
        // routes via FailureTransition.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("reviewer1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("reviewer2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any())).thenThrow(new RuntimeException("Simulated agent crash"));
        when(agent2.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                """
                {"score": 90, "approved": true, "recommendation": "Good"}"""));

        var agents =
                Map.of(
                        "reviewer1",
                                AgentConfig.builder()
                                        .id("reviewer1")
                                        .role("Reviewer")
                                        .model("test")
                                        .build(),
                        "reviewer2",
                                AgentConfig.builder()
                                        .id("reviewer2")
                                        .role("Reviewer")
                                        .model("test")
                                        .build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "reviewer1", "Review")
                        .branch("b2", "reviewer2", "Review")
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(0, "fail-end")))
                        .build());
        nodes.put("end", end("end"));
        nodes.put("fail-end", failEnd("fail-end"));
        var workflow =
                Workflow.builder()
                        .id("branch-crash")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        // Crashed branch produces FAILURE, consensus fails → FailureTransition
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    // — helpers ———————————————————————————————————————————————————————————

    private Workflow buildMajorityWorkflow() {
        var agents =
                Map.of(
                        "reviewer1",
                                AgentConfig.builder()
                                        .id("reviewer1")
                                        .role("Reviewer")
                                        .model("test")
                                        .build(),
                        "reviewer2",
                                AgentConfig.builder()
                                        .id("reviewer2")
                                        .role("Reviewer")
                                        .model("test")
                                        .build(),
                        "reviewer3",
                                AgentConfig.builder()
                                        .id("reviewer3")
                                        .role("Reviewer")
                                        .model("test")
                                        .build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "reviewer1", "Review this work")
                        .branch("b2", "reviewer2", "Review this work")
                        .branch("b3", "reviewer3", "Review this work")
                        .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        return Workflow.builder()
                .id("majority-vote")
                .agents(agents)
                .nodes(nodes)
                .startNode("parallel")
                .build();
    }
}
