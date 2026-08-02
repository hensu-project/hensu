package io.hensu.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.hensu.core.agent.*;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.action.Action;
import io.hensu.core.execution.action.ActionExecutor;
import io.hensu.core.execution.action.ActionExecutor.ActionResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.DefaultToolRegistry;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolRegistry;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolLoopRunnerTest {

    private ActionExecutor mockActionExecutor;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        mockActionExecutor = mock(ActionExecutor.class);
        toolRegistry = new DefaultToolRegistry();
        ToolDefinition searchTool =
                ToolDefinition.of(
                        "search",
                        "Search for information",
                        List.of(
                                ToolDefinition.ParameterDef.required(
                                        "query", "string", "Search query")));
        toolRegistry.register(searchTool);
    }

    @Nested
    class HappyPath {

        @Test
        void shouldExecuteToolAndReturnFinalResponse() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("Found results", "search results here"));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    "[TOOL_CALL] search query=test\n---TURN---\nFinal answer based on search");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Find info", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Final answer based on search");
            verify(mockActionExecutor).execute(any(Action.Send.class), any());
        }

        @Test
        void shouldHandleMultipleToolRounds() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("ok", "result1"))
                    .thenReturn(ActionResult.success("ok", "result2"));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=first
                            ---TURN---
                            [TOOL_CALL] search query=second
                            ---TURN---
                            Combined answer""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Multi search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Combined answer");
            verify(mockActionExecutor, times(2)).execute(any(Action.Send.class), any());
        }
    }

    @Nested
    class CapExhaustion {

        @Test
        void shouldSucceedWhenModelCooperatesAfterCapExhaustion() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("ok", "tool output"));

            AgentConfig config =
                    AgentConfig.builder()
                            .id("test-agent")
                            .role("tester")
                            .model("stub")
                            .tools(List.of("search"))
                            .maxToolCalls(1)
                            .build();

            StubAgent agent = new StubAgent("test-agent", config);
            // First turn: tool call (counts as 1, hitting cap)
            // After cap exhaustion feedback, stub returns final text
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Final answer after budget warning""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString())
                    .isEqualTo("Final answer after budget warning");
        }

        @Test
        void shouldFailWhenModelIgnoresBudgetWarning() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("ok", "tool output"));

            AgentConfig config =
                    AgentConfig.builder()
                            .id("test-agent")
                            .role("tester")
                            .model("stub")
                            .tools(List.of("search"))
                            .maxToolCalls(1)
                            .build();

            StubAgent agent = new StubAgent("test-agent", config);
            // First: tool call (hits cap after execution), then after cap-exhaustion
            // feedback the stub still tries another tool call instead of answering
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            [TOOL_CALL] search query=another
                            ---TURN---
                            [TOOL_CALL] search query=yet-another""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("budget exhaustion");
        }
    }

    @Nested
    class UnknownTool {

        @Test
        void shouldFeedBackUnknownToolAndEventuallySucceed() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("ok", "search output"));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            // First turn: hallucinated tool, second turn: real tool, third: final
            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] nonexistent query=test
                            ---TURN---
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Got it""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            // Only the real tool call should have gone to the action executor
            verify(mockActionExecutor, times(1)).execute(any(Action.Send.class), any());
        }
    }

    @Nested
    class ToolFailure {

        @Test
        void shouldFeedBackToolFailure() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.failure("Tool crashed"));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(
                    agent,
                    """
                            [TOOL_CALL] search query=test
                            ---TURN---
                            Handled the failure gracefully""");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.getOutput().toString()).isEqualTo("Handled the failure gracefully");
        }
    }

    @Nested
    class EngagementCheck {

        @Test
        void shouldFailWhenAgentNotToolCapable() {
            Agent nonToolAgent = mock(Agent.class);
            when(nonToolAgent.getConfig()).thenReturn(agentConfig(List.of("search")));

            ExecutionContext ctx = buildContext(agentConfig(List.of("search")), nonToolAgent);

            NodeResult result =
                    ToolLoopRunner.execute("node1", "test-agent", "Prompt", nonToolAgent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("does not implement ToolCapable");
        }

        @Test
        void shouldFailWhenToolNotInRegistry() {
            AgentConfig config = agentConfig(List.of("nonexistent-tool"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "Should not reach here");

            ExecutionContext ctx = buildContext(config, agent);

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Prompt", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("Unresolvable tools");
        }

        @Test
        void shouldFailWhenNoActionExecutorConfigured() {
            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "whatever");

            // Build context WITHOUT action executor
            ExecutionContext ctx =
                    ExecutionContext.builder()
                            .state(buildState())
                            .workflow(buildWorkflow())
                            .listener(ExecutionListener.NOOP)
                            .agentRegistry(buildAgentRegistry(config, agent))
                            .toolRegistry(toolRegistry)
                            .build();

            NodeResult result = ToolLoopRunner.execute("node1", "test-agent", "Prompt", agent, ctx);

            assertThat(result.getStatus()).isEqualTo(ResultStatus.FAILURE);
            assertThat(result.getOutput().toString()).contains("no ActionExecutor");
        }
    }

    @Nested
    class RawPayload {

        @Test
        void shouldSendRawPayloadToActionExecutor() {
            when(mockActionExecutor.execute(any(Action.class), any()))
                    .thenReturn(ActionResult.success("ok", "output"));

            AgentConfig config = agentConfig(List.of("search"));
            StubAgent agent = new StubAgent("test-agent", config);

            registerStubResponse(agent, "[TOOL_CALL] search query={context_var}\n---TURN---\nDone");

            ExecutionContext ctx = buildContext(config, agent);

            ToolLoopRunner.execute("node1", "test-agent", "Search", agent, ctx);

            // Verify the Action.Send was created with rawPayload=true
            var captor = org.mockito.ArgumentCaptor.forClass(Action.class);
            verify(mockActionExecutor).execute(captor.capture(), any());
            Action.Send send = (Action.Send) captor.getValue();
            assertThat(send.isRawPayload()).isTrue();
        }
    }

    // ——— Helpers ———————————————————————————————————————————————————————

    private AgentConfig agentConfig(List<String> tools) {
        return AgentConfig.builder()
                .id("test-agent")
                .role("tester")
                .model("stub")
                .tools(tools)
                .build();
    }

    private void registerStubResponse(StubAgent agent, String response) {
        io.hensu.core.agent.stub.StubResponseRegistry.getInstance()
                .registerResponse("test-agent", response);
    }

    private ExecutionContext buildContext(AgentConfig config, Agent agent) {
        return ExecutionContext.builder()
                .state(buildState())
                .workflow(buildWorkflow())
                .listener(ExecutionListener.NOOP)
                .agentRegistry(buildAgentRegistry(config, agent))
                .actionExecutor(mockActionExecutor)
                .toolRegistry(toolRegistry)
                .build();
    }

    private AgentRegistry buildAgentRegistry(AgentConfig config, Agent agent) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getAgent("test-agent")).thenReturn(java.util.Optional.of(agent));
        return registry;
    }

    private HensuState buildState() {
        return new HensuState.Builder()
                .executionId("test-exec")
                .workflowId("test-wf")
                .currentNode("node1")
                .context(new HashMap<>(Map.of("current_node", "node1")))
                .history(new ExecutionHistory())
                .build();
    }

    private Workflow buildWorkflow() {
        StandardNode node1 =
                StandardNode.builder()
                        .id("node1")
                        .agentId("test-agent")
                        .prompt("test")
                        .transitionRules(List.of(new SuccessTransition("end")))
                        .build();
        EndNode end = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

        return Workflow.builder()
                .id("test-wf")
                .startNode("node1")
                .nodes(Map.of("node1", node1, "end", end))
                .build();
    }
}
