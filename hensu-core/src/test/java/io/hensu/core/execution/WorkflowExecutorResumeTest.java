package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.WorkflowTest;
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
}
