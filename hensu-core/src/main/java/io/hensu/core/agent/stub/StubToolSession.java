package io.hensu.core.agent.stub;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.agent.TurnJournal;
import io.hensu.core.tool.ToolCallResult;
import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;

/// Scripted tool session for testing.
///
/// Parses the stub response text into turns separated by `---TURN---`.
/// A turn starting with `[TOOL_CALL] toolName k=v k2=v2` produces a
/// {@link AgentResponse.ToolRequest}; plain-text turns produce a
/// {@link AgentResponse.TextResponse}. Script exhaustion on submit
/// yields an {@link AgentResponse.Error}.
///
/// Each turn the session hands out goes through its {@link TurnJournal} as the
/// model's answers do in a real adapter, so a recovered stub run consumes the
/// journaled turns exactly as a recovered model-backed run would.
class StubToolSession implements ToolSession {

    private final String[] turns;
    private final TurnJournal journal;
    private int cursor = 0;

    StubToolSession(String responseScript, TurnJournal journal) {
        this.turns = responseScript.split("---TURN---");
        this.journal = journal;
    }

    @Override
    public AgentResponse start() {
        return nextResponse();
    }

    @Override
    public AgentResponse submit(ToolCallResult result) {
        return nextResponse();
    }

    @Override
    public void compact() {
        // no-op: scripted session has no real context window
    }

    @Override
    public void close() {
        // no-op: no resources to release
    }

    private AgentResponse nextResponse() {
        int index = cursor++;
        String turn;
        try {
            // Index 0 is the opening conversation in a real adapter; the stub has none,
            // so its scripted answers start at 1. The journal is asked first: a recovered
            // run replays recorded turns whatever the script now says, and the script is
            // consulted – and can run out – only for a turn nobody recorded.
            turn =
                    journal.turn(
                            index + 1,
                            () -> {
                                if (index >= turns.length) {
                                    throw new ScriptExhausted();
                                }
                                return turns[index].trim();
                            });
        } catch (ScriptExhausted e) {
            return AgentResponse.Error.of("Stub tool session script exhausted");
        }

        if (turn.startsWith("[TOOL_CALL]")) {
            return parseToolCall(turn);
        }

        return AgentResponse.TextResponse.of(turn);
    }

    /// Raised inside the journal's live supplier so that running out of script records
    /// nothing and surfaces as the session's error response.
    private static final class ScriptExhausted extends RuntimeException {
        @Serial private static final long serialVersionUID = -4999742827824239665L;

        ScriptExhausted() {
            super(null, null, false, false);
        }
    }

    private AgentResponse.ToolRequest parseToolCall(String turn) {
        // Format: [TOOL_CALL] toolName k=v k2=v2
        String content = turn.substring("[TOOL_CALL]".length()).trim();
        String[] parts = content.split("\\s+");

        String toolName = parts.length > 0 ? parts[0] : "unknown";
        Map<String, Object> arguments = new LinkedHashMap<>();

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                arguments.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }

        return AgentResponse.ToolRequest.of(toolName, arguments);
    }
}
