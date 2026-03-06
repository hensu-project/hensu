package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

class WorkflowExecutorTest extends WorkflowExecutorTestBase {

    // — Core routing and lifecycle ——————————————————————————————————————————

    @Test
    void shouldExecuteSimpleWorkflowToEnd() throws Exception {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(step("start", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.SUCCESS);
    }

    @Test
    void shouldThrowExceptionWhenNodeNotFound() {
        // "start" transitions to "missing-node" which is absent from the nodes map.
        // The executor must throw at runtime when it tries to resolve the next step.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(List.of(new SuccessTransition("missing-node")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Node not found");
    }

    @Test
    void shouldRecordExecutionHistory() throws Exception {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(step("step1", "step2"))
                        .node(step("step2", "end"))
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        // Both steps must appear in history — proves the loop visits each node once.
        assertThat(((ExecutionResult.Completed) result).getFinalState().getHistory().getSteps())
                .hasSize(2);
    }

    // — Behavioral tests ————————————————————————————————————————————————————

    @Test
    void shouldHandleAgentFailureResponse() throws Exception {
        // Agent returns Error → executor follows FailureTransition → failure-end.
        var start =
                StandardNode.builder()
                        .id("start")
                        .agentId("test-agent")
                        .prompt("Process input")
                        .transitionRules(
                                List.of(
                                        new SuccessTransition("success-end"),
                                        new FailureTransition(0, "failure-end")))
                        .build();
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(start)
                        .node(end("success-end"))
                        .node(failEnd("failure-end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.Error.of("Agent error"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldExecuteToFailureEnd() throws Exception {
        // Agent succeeds but the workflow design routes to a FAILURE end node.
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Process input")
                                        .transitionRules(
                                                List.of(new SuccessTransition("failure-end")))
                                        .build())
                        .node(failEnd("failure-end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.FAILURE);
    }

    @Test
    void shouldThrowWhenNoValidTransition() {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Process input")
                                        .transitionRules(List.of()) // no transitions
                                        .build())
                        .node(end("end"))
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any()))
                .thenReturn(AgentResponse.TextResponse.of("Agent output"));

        assertThatThrownBy(() -> executor.execute(workflow, new HashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid transition");
    }
}
