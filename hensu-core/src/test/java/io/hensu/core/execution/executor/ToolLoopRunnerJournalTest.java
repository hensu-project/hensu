package io.hensu.core.execution.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.AgentConfig;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.stub.StubAgent;
import io.hensu.core.agent.stub.StubResponseRegistry;
import io.hensu.core.execution.RecordingJournal;
import io.hensu.core.execution.RunPreemptedException;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import io.hensu.core.tool.ToolCall;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.ToolRouter;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.EndNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// The tool loop under a journal: a recovered node attempt replays its predecessor's
/// decisions instead of re-deciding them, and reaches its calls under the same keys.
class ToolLoopRunnerJournalTest {

    private static final ToolDefinition SEARCH =
            ToolDefinition.of(
                    "search",
                    "Search",
                    List.of(ToolDefinition.ParameterDef.required("query", "string", "Query")));

    /// A provider that, like the server's, records each call's outcome under its key and
    /// hands a repeated key the recorded outcome without invoking anything.
    private static final class RecordingProvider implements ToolProvider {
        final List<ToolCall> invoked = new ArrayList<>();
        final List<String> keysSeen = new ArrayList<>();
        final Map<String, ToolCallResult> outcomes = new ConcurrentHashMap<>();

        @Override
        public List<ToolDefinition> tools() {
            return List.of(SEARCH);
        }

        @Override
        public ToolCallResult call(ToolCall call, Map<String, Object> context) {
            keysSeen.add(call.key());
            return outcomes.computeIfAbsent(
                    call.key(),
                    _ -> {
                        invoked.add(call);
                        return ToolCallResult.success(
                                call.toolName(), "found " + call.arguments().get("query"));
                    });
        }
    }

    @AfterEach
    void clearStubs() {
        StubResponseRegistry.getInstance().clearResponses();
    }

    @Test
    void aRecoveredAttemptReplaysTheRecordedDecisionsAndOutcomes() {
        RecordingProvider provider = new RecordingProvider();
        RecordingJournal journal = new RecordingJournal();
        script(
                "[TOOL_CALL] search query=first\n---TURN---\n[TOOL_CALL] search query=second\n---TURN---\nanswer one");
        NodeResult original = run(provider, journal, 2);
        int recordedTurns = journal.liveTurns.get();

        // The model would now answer differently – a recovery must not ask it.
        script("[TOOL_CALL] search query=elsewhere\n---TURN---\nanswer two");
        NodeResult recovered = run(provider, journal, 2);

        assertThat(recovered.getOutput()).isEqualTo(original.getOutput()).isEqualTo("answer one");
        assertThat(journal.liveTurns.get()).isEqualTo(recordedTurns);
        assertThat(provider.keysSeen).containsExactly("2#1", "2#2", "2#1", "2#2");
        assertThat(provider.invoked)
                .extracting(call -> call.arguments().get("query"))
                .containsExactly("first", "second");
    }

    @Test
    void aPreemptionFromTheToolSeamStopsTheLoopInsteadOfBecomingAToolFailure() {
        ToolProvider preempting =
                new ToolProvider() {
                    @Override
                    public List<ToolDefinition> tools() {
                        return List.of(SEARCH);
                    }

                    @Override
                    public ToolCallResult call(ToolCall call, Map<String, Object> context) {
                        throw new RunPreemptedException("claim superseded");
                    }
                };
        script("[TOOL_CALL] search query=a\n---TURN---\ndone");

        assertThatThrownBy(() -> run(preempting, new RecordingJournal(), 0))
                .isInstanceOf(RunPreemptedException.class);
    }

    private static void script(String turns) {
        StubResponseRegistry.getInstance().registerResponse("agent", turns);
    }

    private static NodeResult run(ToolProvider provider, RecordingJournal journal, long step) {
        AgentConfig config =
                AgentConfig.builder()
                        .id("agent")
                        .role("r")
                        .model("stub")
                        .tools(List.of("search"))
                        .build();
        StubAgent agent = new StubAgent("agent", config);
        AgentRegistry agents = mock(AgentRegistry.class);
        when(agents.getAgent("agent")).thenReturn(Optional.of(agent));
        HensuState state =
                new HensuState.Builder()
                        .executionId("exec")
                        .workflowId("wf")
                        .currentNode("node")
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .step(step)
                        .build();
        ExecutionContext ctx =
                ExecutionContext.builder()
                        .state(state)
                        .workflow(
                                Workflow.builder()
                                        .id("wf")
                                        .startNode("node")
                                        .nodes(
                                                Map.of(
                                                        "node",
                                                        EndNode.builder()
                                                                .id("node")
                                                                .status(ExitStatus.SUCCESS)
                                                                .build()))
                                        .build())
                        .agentRegistry(agents)
                        .toolRouter(new ToolRouter(List.of(provider)))
                        .journal(journal)
                        .build();
        NodeResult result = ToolLoopRunner.execute("node", "agent", "prompt", agent, ctx);
        assertThat(result.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        return result;
    }
}
