package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.state.HensuSnapshot;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.ForkNode;
import io.hensu.core.workflow.node.JoinNode;
import io.hensu.core.workflow.node.MergeStrategy;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// How the executor names node attempts and where a recovered run resumes.
class WorkflowExecutorJournalTest extends WorkflowExecutorTestBase {

    @Test
    void eachMainFlowCheckpointOpensTheNextStep() throws Exception {
        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("ok"));
        Map<String, Node> nodes = new HashMap<>();
        nodes.put("a", step("a", "b"));
        nodes.put("b", step("b", "c"));
        nodes.put("c", step("c", "end"));
        nodes.put("end", end("end"));
        Workflow workflow =
                Workflow.builder()
                        .id("wf")
                        .agents(Map.of("test-agent", agentCfg()))
                        .nodes(nodes)
                        .startNode("a")
                        .build();
        List<Long> steps = new ArrayList<>();

        executor.execute(
                workflow,
                new HashMap<>(),
                new ExecutionListener() {
                    @Override
                    public void onCheckpoint(HensuState state) {
                        steps.add(state.getStep());
                    }
                });

        assertThat(steps).containsExactly(0L, 1L, 2L);
    }

    /// A fork is re-entered from the checkpoint before it; each branch resumes from what
    /// it journaled, so a branch that had reached the join does not run again.
    @Test
    void aRecoveredForkDoesNotRunBranchesThatAlreadyFinished() throws Exception {
        Agent agentA = mock(Agent.class);
        Agent agentB = mock(Agent.class);
        when(agentRegistry.getAgent("agent-a")).thenReturn(Optional.of(agentA));
        when(agentRegistry.getAgent("agent-b")).thenReturn(Optional.of(agentB));
        when(agentA.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("A"));
        when(agentB.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("B"));
        Workflow workflow = forkJoin();
        RecordingJournal journal = new RecordingJournal();
        List<HensuSnapshot> checkpoints = new ArrayList<>();
        ExecutionListener listener =
                new ExecutionListener() {
                    @Override
                    public void onCheckpoint(HensuState state) {
                        checkpoints.add(HensuSnapshot.from(state, "checkpoint"));
                    }
                };

        HensuState initial =
                new HensuState.Builder()
                        .executionId("exec-1")
                        .workflowId(workflow.getId())
                        .currentNode(workflow.getStartNode())
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        executor.executeFrom(workflow, initial, listener, journal);

        // The node crashed after the branches finished but before the join's checkpoint;
        // the next run re-enters the fork from the checkpoint before it.
        HensuState atFork = checkpoints.getFirst().toState();
        ExecutionResult recovered = executor.executeFrom(workflow, atFork, listener, journal);

        assertThat(recovered).isInstanceOf(ExecutionResult.Completed.class);
        verify(agentA, times(1)).execute(any(), any());
        verify(agentB, times(1)).execute(any(), any());
    }

    private static Workflow forkJoin() {
        Map<String, Node> nodes = new HashMap<>();
        nodes.put(
                "taskA",
                StandardNode.builder()
                        .id("taskA")
                        .agentId("agent-a")
                        .prompt("A")
                        .transitionRules(List.of(new SuccessTransition("join1")))
                        .build());
        nodes.put(
                "taskB",
                StandardNode.builder()
                        .id("taskB")
                        .agentId("agent-b")
                        .prompt("B")
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
                        .writes("fork_results")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build());
        nodes.put("end", end("end"));
        return Workflow.builder()
                .id("fork-journal")
                .agents(
                        Map.of(
                                "agent-a",
                                AgentConfig.builder().id("agent-a").role("A").model("test").build(),
                                "agent-b",
                                AgentConfig.builder()
                                        .id("agent-b")
                                        .role("B")
                                        .model("test")
                                        .build()))
                .nodes(nodes)
                .startNode("fork1")
                .build();
    }
}
