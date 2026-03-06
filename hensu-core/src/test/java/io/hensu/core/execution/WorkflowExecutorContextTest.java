package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.WorkflowTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkflowExecutorContextTest extends WorkflowExecutorTestBase {

    @Test
    void shouldNotLeakContextBetweenSeparateExecutions() throws Exception {
        // If the executor reuses state between calls, "secret" from execution 1
        // would appear in the final state of execution 2.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("isolation-test")
                        .agent(agentCfg())
                        .startNode(step("start", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("output"));

        var ctx1 = new HashMap<String, Object>();
        ctx1.put("secret", "sensitive-data");
        var result1 = executor.execute(workflow, ctx1);
        var result2 = executor.execute(workflow, new HashMap<>());

        assertThat(result1).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(result2).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result2).getFinalState().getContext())
                .doesNotContainKey("secret");
    }

    @Test
    void shouldHandleConcurrentExecutionsWithoutCorruption() throws Exception {
        // The Javadoc states the executor is conditionally thread-safe: it holds only
        // final fields and creates a fresh HensuState per call. The one shared side-effect
        // is registerWorkflowAgents() which has a benign race on DefaultAgentRegistry.
        // This test fires 10 threads simultaneously to expose CME, deadlock, or state
        // corruption that the Javadoc claims cannot occur.
        int threadCount = 10;
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("concurrent-test")
                        .agent(agentCfg())
                        .startNode(step("start", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("output"));

        var startLatch = new CountDownLatch(1);
        List<CompletableFuture<ExecutionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    startLatch.await();
                                    return executor.execute(workflow, new HashMap<>());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }));
        }

        startLatch.countDown();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);

        for (var future : futures) {
            assertThat(future.join()).isInstanceOf(ExecutionResult.Completed.class);
            assertThat(((ExecutionResult.Completed) future.join()).getExitStatus())
                    .isEqualTo(ExitStatus.SUCCESS);
        }
    }
}
