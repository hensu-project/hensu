package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.parallel.ConsensusStrategy;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.rubric.RubricNotFoundException;
import io.hensu.core.rubric.evaluator.RubricEvaluation;
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

    // — Majority vote ————————————————————————————————————————————————————

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
                // 2 approve / 1 reject → majority approves → SUCCESS
                Arguments.of(
                        "I approve this work. Score: 90",
                        "I approve. Score: 85",
                        "I reject this. Score: 30",
                        ExitStatus.SUCCESS),
                // 1 approve / 2 reject → majority rejects → FAILURE
                Arguments.of(
                        "I approve. Score: 90",
                        "I reject this. Score: 20",
                        "I reject this. Score: 15",
                        ExitStatus.FAILURE));
    }

    // — No-consensus collection ——————————————————————————————————————————

    @Test
    void shouldCollectBranchOutputsWithoutConsensus() throws Exception {
        // No consensus config → all branch outputs collected → always SUCCESS.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("writer1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("writer2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Draft from writer 1"));
        when(agent2.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Draft from writer 2"));

        var agents =
                Map.of(
                        "writer1",
                                AgentConfig.builder()
                                        .id("writer1")
                                        .role("Writer")
                                        .model("test")
                                        .build(),
                        "writer2",
                                AgentConfig.builder()
                                        .id("writer2")
                                        .role("Writer")
                                        .model("test")
                                        .build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "writer1", "Write draft")
                        .branch("b2", "writer2", "Write draft")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        var workflow =
                Workflow.builder()
                        .id("no-consensus")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    // — Rubric-based branch evaluation ———————————————————————————————————

    @Test
    void shouldEvaluateBranchRubricInConsensus() throws Exception {
        // 3 branches with rubricId; r1/r2 pass (90/85), r3 fails (40).
        // 2/3 pass → MAJORITY_VOTE → consensus reached → SUCCESS.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        var agent3 = mock(Agent.class);
        when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
        when(agentRegistry.getAgent("r3")).thenReturn(Optional.of(agent3));
        when(agent1.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Good output"));
        when(agent2.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Great output"));
        when(agent3.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Poor output"));
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(90.0)
                                .passed(true)
                                .build())
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(85.0)
                                .passed(true)
                                .build())
                .thenReturn(
                        RubricEvaluation.builder()
                                .rubricId("quality")
                                .score(40.0)
                                .passed(false)
                                .build());

        var agents =
                Map.of(
                        "r1",
                        AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                        "r2",
                        AgentConfig.builder().id("r2").role("Reviewer").model("test").build(),
                        "r3",
                        AgentConfig.builder().id("r3").role("Reviewer").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "r1", "Review", "quality")
                        .branch("b2", "r2", "Review", "quality")
                        .branch("b3", "r3", "Review", "quality")
                        .consensus(null, ConsensusStrategy.MAJORITY_VOTE)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder()
                        .id("rubric-consensus")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .rubrics(Map.of("quality", "test-path"))
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldSkipRubricWhenBranchHasNoRubricId() throws Exception {
        // Branches without rubricId fall back to keyword heuristics.
        // "approve" keyword → APPROVE for both → UNANIMOUS → SUCCESS.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));
        when(agent2.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));

        var agents =
                Map.of(
                        "r1",
                        AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                        "r2",
                        AgentConfig.builder().id("r2").role("Reviewer").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "r1", "Review")
                        .branch("b2", "r2", "Review")
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder()
                        .id("no-rubric-consensus")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldFailWhenUnanimousConsensusHasOneDissent() throws Exception {
        // UNANIMOUS requires every branch to approve. Two approve, one rejects.
        // The single "reject" keyword must break consensus → FAILURE transition.
        // If the implementation treats UNANIMOUS like MAJORITY_VOTE, this test catches it.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        var agent3 = mock(Agent.class);
        when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
        when(agentRegistry.getAgent("r3")).thenReturn(Optional.of(agent3));
        when(agent1.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));
        when(agent2.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));
        when(agent3.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I reject this"));

        var agents =
                Map.of(
                        "r1",
                        AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                        "r2",
                        AgentConfig.builder().id("r2").role("Reviewer").model("test").build(),
                        "r3",
                        AgentConfig.builder().id("r3").role("Reviewer").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "r1", "Review")
                        .branch("b2", "r2", "Review")
                        .branch("b3", "r3", "Review")
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder()
                        .id("unanimous-dissent")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .build();

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldHandleRubricEvaluationFailureGracefully() throws Exception {
        // rubricEngine throws RubricNotFoundException for branch evaluation.
        // Executor must fall back to keyword heuristics rather than propagating the error.
        var agent1 = mock(Agent.class);
        var agent2 = mock(Agent.class);
        when(agentRegistry.getAgent("r1")).thenReturn(Optional.of(agent1));
        when(agentRegistry.getAgent("r2")).thenReturn(Optional.of(agent2));
        when(agent1.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));
        when(agent2.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("I approve this"));
        when(rubricEngine.exists("quality")).thenReturn(true);
        when(rubricEngine.evaluate(eq("quality"), any(), any()))
                .thenThrow(new RubricNotFoundException("Rubric not found: quality"));

        var agents =
                Map.of(
                        "r1",
                        AgentConfig.builder().id("r1").role("Reviewer").model("test").build(),
                        "r2",
                        AgentConfig.builder().id("r2").role("Reviewer").model("test").build());
        var nodes = new HashMap<String, Node>();
        nodes.put(
                "parallel",
                ParallelNode.builder("parallel")
                        .branch("b1", "r1", "Review", "quality")
                        .branch("b2", "r2", "Review", "quality")
                        .consensus(null, ConsensusStrategy.UNANIMOUS)
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build());
        nodes.put("success-end", end("success-end"));
        nodes.put("failure-end", failEnd("failure-end"));
        var workflow =
                Workflow.builder()
                        .id("rubric-failure-fallback")
                        .agents(agents)
                        .nodes(nodes)
                        .startNode("parallel")
                        .rubrics(Map.of("quality", "test-path"))
                        .build();

        // rubric fails → keyword "approve" fallback → unanimous → SUCCESS
        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
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
