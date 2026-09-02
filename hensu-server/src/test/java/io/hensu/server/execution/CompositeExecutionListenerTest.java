package io.hensu.server.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.tool.ToolCallEvent;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolResultEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompositeExecutionListenerTest {

    @Test
    void shouldFanToolEventsToEveryDelegateEvenWhenOneThrows() {
        // The composite is the server's only fan-out point. A delegate that throws
        // used to abort the loop, so a broken sink silently cost every other sink
        // its record of what an unattended agent ran.
        RecordingListener first = new RecordingListener();
        RecordingListener last = new RecordingListener();

        ExecutionListener composite =
                new CompositeExecutionListener(first, new ThrowingListener(), last);

        composite.onToolCall(ToolCallEvent.now("node1", "agent1", "search", Map.of("q", "hensu")));
        composite.onToolResult(
                ToolResultEvent.now(
                        "node1", "agent1", ToolCallResult.success("search", "hits"), 12L));

        assertThat(first.calls).extracting(ToolCallEvent::toolName).containsExactly("search");
        assertThat(last.calls).extracting(ToolCallEvent::toolName).containsExactly("search");
        assertThat(first.results).extracting(ToolResultEvent::toolName).containsExactly("search");
        assertThat(last.results).extracting(ToolResultEvent::toolName).containsExactly("search");
    }

    private static final class RecordingListener implements ExecutionListener {

        private final List<ToolCallEvent> calls = new ArrayList<>();
        private final List<ToolResultEvent> results = new ArrayList<>();

        @Override
        public void onToolCall(ToolCallEvent event) {
            calls.add(event);
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            results.add(event);
        }
    }

    private static final class ThrowingListener implements ExecutionListener {

        @Override
        public void onToolCall(ToolCallEvent event) {
            throw new IllegalStateException("audit sink is down");
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            throw new IllegalStateException("audit sink is down");
        }
    }
}
