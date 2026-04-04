package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.FailureTransition;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowExecutorRetryTest extends WorkflowExecutorTestBase {

    @Test
    void shouldRetryOnFailureAndSucceedOnSecondAttempt() throws Exception {
        // FailureTransition(maxRetries=3) — agent fails once then succeeds → SUCCESS end.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Do work")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(3, "fallback")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("retry")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("end"))
                        .node(failEnd("fallback"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.Error.of("Transient error"))
                .thenReturn(AgentResponse.TextResponse.of("Success"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldExhaustRetriesAndTransitionToFallback() throws Exception {
        // 1 original + 3 retries all fail → FailureTransition fires → fallback (FAILURE).
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Do work")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(3, "fallback")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("exhaust-retry")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("end"))
                        .node(failEnd("fallback"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.Error.of("Persistent error"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldPassIdenticalResolvedPromptToEachRetryAttempt() throws Exception {
        // The ArgumentCaptor captures the exact resolved prompt on each call.
        // If the failed output from attempt 1 bled into context, attempt 2 would
        // resolve "{input}" to something other than "original" and the assertion fails.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Input: {input}")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("end"),
                                        new FailureTransition(2, "fallback")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("retry-prompt-capture")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("end"))
                        .node(failEnd("fallback"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.Error.of("fail"))
                .thenReturn(AgentResponse.TextResponse.of("ok"));

        var ctx = new HashMap<String, Object>();
        ctx.put("input", "original");
        executor.execute(workflow, ctx);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAgent, times(2)).execute(promptCaptor.capture(), any());
        assertThat(promptCaptor.getAllValues())
                .containsExactly("Input: original", "Input: original");
    }
}
