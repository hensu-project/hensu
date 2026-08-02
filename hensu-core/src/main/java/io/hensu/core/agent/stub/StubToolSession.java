package io.hensu.core.agent.stub;

import io.hensu.core.agent.AgentResponse;
import io.hensu.core.agent.ToolSession;
import io.hensu.core.tool.ToolCallResult;
import java.util.LinkedHashMap;
import java.util.Map;

/// Scripted tool session for testing.
///
/// Parses the stub response text into turns separated by `---TURN---`.
/// A turn starting with `[TOOL_CALL] toolName k=v k2=v2` produces a
/// {@link AgentResponse.ToolRequest}; plain-text turns produce a
/// {@link AgentResponse.TextResponse}. Script exhaustion on submit
/// yields an {@link AgentResponse.Error}.
class StubToolSession implements ToolSession {

    private final String[] turns;
    private int cursor = 0;

    StubToolSession(String responseScript) {
        this.turns = responseScript.split("---TURN---");
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
        if (cursor >= turns.length) {
            return AgentResponse.Error.of("Stub tool session script exhausted");
        }

        String turn = turns[cursor++].trim();

        if (turn.startsWith("[TOOL_CALL]")) {
            return parseToolCall(turn);
        }

        return AgentResponse.TextResponse.of(turn);
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
