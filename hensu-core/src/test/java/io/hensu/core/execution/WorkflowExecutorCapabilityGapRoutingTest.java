package io.hensu.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.executor.DefaultNodeExecutorRegistry;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.state.HensuState;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.tool.CapabilityGaps;
import io.hensu.core.tool.StubToolProvider;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.Condition;
import io.hensu.core.workflow.transition.ConditionTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// A run blocked on capability routes itself, through an ordinary transition.
///
/// This is the whole point of the `_capability_gaps` channel: the node did its work and
/// reported, and the graph decides what that means. No new transition type, no decorator,
/// no engine branch on human presence — a `ConditionTransition` on a reserved key, exactly
/// like one on a variable the agent wrote.
class WorkflowExecutorCapabilityGapRoutingTest extends WorkflowExecutorTestBase {

    private static final ToolDefinition SEARCH =
            ToolDefinition.of("search", "Search for information", List.of());

    @BeforeEach
    void wireToolSurface() {
        var registry = new DefaultNodeExecutorRegistry();
        executor =
                new WorkflowExecutor(
                        registry,
                        agentRegistry,
                        rubricEngine,
                        createCoordinator(registry, ReviewHandler.AUTO_APPROVE, rubricEngine),
                        null,
                        new SimpleTemplateResolver(),
                        null,
                        new ToolRouter(
                                List.of(StubToolProvider.alwaysSucceeding("results", SEARCH))));
    }

    @Test
    void shouldRouteABlockedRunToItsEscalationArm() throws Exception {
        StubAgent agent = agentAskingFor("deploy environment=prod");

        var result = executor.execute(workflow(), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        var completed = (ExecutionResult.Completed) result;
        assertThat(completed.getFinalState().getCurrentNode()).isEqualTo("blocked");
        assertThat(CapabilityGaps.of(completed.getFinalState().getContext()))
                .singleElement()
                .satisfies(
                        record ->
                                assertThat(record)
                                        .containsEntry(CapabilityGaps.FIELD_TOOL, "deploy")
                                        .containsEntry(CapabilityGaps.FIELD_GATE, "UNKNOWN_TOOL"));
        assertThat(agent).isNotNull();
    }

    @Test
    void shouldTakeTheOrdinaryArmWhenEverythingTheAgentAskedForWasGranted() throws Exception {
        agentAskingFor("search query=hensu");

        var result = executor.execute(workflow(), new HashMap<>());

        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        HensuState state = ((ExecutionResult.Completed) result).getFinalState();
        assertThat(state.getCurrentNode()).isEqualTo("done");
        // A run with no gaps must not carry the key at all, or every workflow routing on
        // it would need to distinguish "absent" from "zero".
        assertThat(state.getContext()).doesNotContainKey(CapabilityGaps.COUNT_KEY);
    }

    private StubAgent agentAskingFor(String toolCall) {
        AgentConfig config =
                AgentConfig.builder()
                        .id("test-agent")
                        .role("Test")
                        .model("stub")
                        .tools(List.of("search"))
                        .build();
        StubAgent agent = new StubAgent("test-agent", config);
        StubResponseRegistry.getInstance()
                .registerResponse("worker", "[TOOL_CALL] " + toolCall + "\n---TURN---\nReported");
        when(agentRegistry.getAgent(any())).thenReturn(Optional.of(agent));
        return agent;
    }

    private static Workflow workflow() {
        StandardNode worker =
                StandardNode.builder()
                        .id("worker")
                        .agentId("test-agent")
                        .prompt("Do work")
                        .transitionRules(
                                List.of(
                                        new ConditionTransition(
                                                CapabilityGaps.COUNT_KEY,
                                                new Condition.Compare(Condition.Op.GTE, 1),
                                                "blocked"),
                                        new io.hensu.core.workflow.transition.SuccessTransition(
                                                "done")))
                        .build();

        return WorkflowTest.TestWorkflowBuilder.create("gap-routing")
                .agent(
                        AgentConfig.builder()
                                .id("test-agent")
                                .role("Test")
                                .model("stub")
                                .tools(List.of("search"))
                                .build())
                .startNode(worker)
                .node(end("done"))
                .node(failEnd("blocked"))
                .build();
    }
}
