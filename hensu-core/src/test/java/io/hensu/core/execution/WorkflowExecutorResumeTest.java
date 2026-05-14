package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.ExecutionPhase;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.WorkflowTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowExecutorResumeTest extends WorkflowExecutorTestBase {

    @Test
    void shouldResumeWorkflowFromSavedState() throws Exception {
        // Resume from step2 with a pre-populated saved state.
        // step1 must NOT execute — only step2 runs. History size proves it.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("resume-test")
                        .agent(agentCfg())
                        .startNode(step("step1", "step2"))
                        .node(step("step2", "end"))
                        .node(end("end"))
                        .build();

        var savedState =
                new HensuState.Builder()
                        .executionId("saved-exec")
                        .workflowId("resume-test")
                        .currentNode("step2")
                        .context(new HashMap<>(Map.of("step1_result", "already computed")))
                        .history(new ExecutionHistory())
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("step2 result"));

        var result = executor.executeFrom(workflow, savedState);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getExitStatus()).isEqualTo(ExitStatus.SUCCESS);
        // Only step2 was executed — step1 was in the saved state already.
        assertThat(completed.getFinalState().getHistory().getSteps()).hasSize(1);
        assertThat(completed.getFinalState().getHistory().getSteps().getFirst().getNodeId())
                .isEqualTo("step2");
    }

    @Test
    void shouldThrowWhenSavedStateCurrentNodeWasRemovedFromWorkflow() {
        // The workflow was redeployed without "deleted-node"; the saved checkpoint still
        // points to it. executeFrom must throw rather than silently hang or skip ahead.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("resume-stale")
                        .agent(agentCfg())
                        .startNode(step("step1", "end"))
                        .node(end("end"))
                        .build();

        var staleState =
                new HensuState.Builder()
                        .executionId("stale-exec")
                        .workflowId("resume-stale")
                        .currentNode("deleted-node")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        assertThatThrownBy(() -> executor.executeFrom(workflow, staleState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void shouldSkipNodeExecutionOnResumeWithAwaitingPhase() throws Exception {
        // Resume with Awaiting must NOT re-run the agent.
        // The post-pipeline runs from TransitionPostProcessor (the processor that
        // would have run after the suspending processor in a real review flow).
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("phase-resume")
                        .agent(agentCfg())
                        .startNode(step("step1", "end"))
                        .node(end("end"))
                        .build();

        var cachedResult = NodeResult.success("already computed", Map.of());
        var phase =
                new ExecutionPhase.Awaiting(
                        "step1", "TransitionPostProcessor", cachedResult, "corr-1", Instant.now());

        var savedState =
                new HensuState.Builder()
                        .executionId("phase-exec")
                        .workflowId("phase-resume")
                        .currentNode("step1")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .phase(phase)
                        .build();

        var result = executor.executeFrom(workflow, savedState);

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        verify(mockAgent, never()).execute(any(), any());
    }

    @Test
    void shouldThrowOnResumeWithTerminalPhase() {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("terminal-resume")
                        .agent(agentCfg())
                        .startNode(step("step1", "end"))
                        .node(end("end"))
                        .build();

        var savedState =
                new HensuState.Builder()
                        .executionId("terminal-exec")
                        .workflowId("terminal-resume")
                        .currentNode("step1")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .phase(ExecutionPhase.TERMINAL)
                        .build();

        assertThatThrownBy(() -> executor.executeFrom(workflow, savedState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void shouldThrowWhenPhaseNodeIdMismatchesCurrentNode() {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("mismatch-resume")
                        .agent(agentCfg())
                        .startNode(step("step1", "end"))
                        .node(end("end"))
                        .build();

        var phase =
                new ExecutionPhase.Awaiting(
                        "wrong-node",
                        "TransitionPostProcessor",
                        NodeResult.success("cached", Map.of()),
                        "corr-1",
                        Instant.now());

        var savedState =
                new HensuState.Builder()
                        .executionId("mismatch-exec")
                        .workflowId("mismatch-resume")
                        .currentNode("step1")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .phase(phase)
                        .build();

        assertThatThrownBy(() -> executor.executeFrom(workflow, savedState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong-node")
                .hasMessageContaining("step1");
    }
}
