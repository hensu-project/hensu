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
import io.hensu.core.workflow.transition.BoundedTransition;
import io.hensu.core.workflow.transition.Condition;
import io.hensu.core.workflow.transition.ConditionTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// End-to-end ralph loop on a single standard node: `BoundedTransition` wrapping a
/// `ConditionTransition` re-executes the node until the agent reports
/// `status == "complete"`, then a condition exit arm leaves the loop. This is the
/// generic replacement for the removed plan-subsystem replan loop and `LoopNode`
/// (#88): if `requiredEngineVars()` wiring, output extraction, stale-value
/// clearing, or retry counters break, the loop either never exits or exits early.
class WorkflowExecutorConditionLoopTest extends WorkflowExecutorTestBase {

    private static StandardNode loopNode(int budget) {
        // Exit arm first (ordering is load-bearing), bounded self-revise arm second.
        return StandardNode.builder()
                .id("worker")
                .agentId("test-agent")
                .prompt("Do work")
                .writes(List.of("status"))
                .transitionRules(
                        List.of(
                                new ConditionTransition(
                                        "status", new Condition.Equals("complete"), "end"),
                                new BoundedTransition(
                                        new ConditionTransition(
                                                "status",
                                                new Condition.NotEquals("complete"),
                                                "worker"),
                                        "condition",
                                        budget,
                                        "escalate")))
                .build();
    }

    @Test
    void shouldIterateUntilConditionExitArmFires() throws Exception {
        // Two in-progress iterations, then complete → SUCCESS end after 3 executions.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("condition-loop")
                        .agent(agentCfg())
                        .startNode(loopNode(5))
                        .node(end("end"))
                        .node(failEnd("escalate"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                "{\"status\": \"in-progress\","
                                        + " \"recommendation\": \"finish section 2\"}"))
                .thenReturn(
                        AgentResponse.TextResponse.of(
                                "{\"status\": \"in-progress\","
                                        + " \"recommendation\": \"polish wording\"}"))
                .thenReturn(AgentResponse.TextResponse.of("{\"status\": \"complete\"}"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
        verify(mockAgent, times(3)).execute(any(), any());
    }

    @Test
    void shouldEscalateWhenLoopBudgetExhausted() throws Exception {
        // Agent never reports complete → budget 2 allows 2 revisions after the
        // initial attempt, then the bounded arm escalates instead of spinning.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("condition-loop-exhaust")
                        .agent(agentCfg())
                        .startNode(loopNode(2))
                        .node(end("end"))
                        .node(failEnd("escalate"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("{\"status\": \"in-progress\"}"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
        verify(mockAgent, times(3)).execute(any(), any());
    }
}
