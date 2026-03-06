package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowExecutorExitStatusTest extends WorkflowExecutorTestBase {

    @Test
    void shouldPropagateCancelExitStatus() throws Exception {
        var workflow =
                WorkflowTest.TestWorkflowBuilder.create("cancel-test")
                        .agent(agentCfg())
                        .startNode(
                                StandardNode.builder()
                                        .id("start")
                                        .agentId("test-agent")
                                        .prompt("Process")
                                        .transitionRules(
                                                List.of(new SuccessTransition("cancel-end")))
                                        .build())
                        .node(EndNode.builder().id("cancel-end").status(ExitStatus.CANCEL).build())
                        .build();

        when(agentRegistry.getAgent("test-agent")).thenReturn(Optional.of(mockAgent));
        when(mockAgent.execute(any(), any())).thenReturn(AgentResponse.TextResponse.of("Done"));

        var result = executor.execute(workflow, new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(((ExecutionResult.Completed) result).getExitStatus())
                .isEqualTo(ExitStatus.CANCEL);
    }
}
