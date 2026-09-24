package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseFramesTest {

    @Test
    void shouldSkipNotificationsAndAnswersToOtherRequests() {
        String stream =
                """
                : keep-alive

                event: message
                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

                data: {"jsonrpc":"2.0","id":"6","result":{"other":true}}

                id: 17
                data: {"jsonrpc":"2.0","id":"7","result":{"mine":true}}

                """;

        assertThat(SseFrames.read(body(stream), "7", "acme")).contains("\"mine\":true");
    }

    @Test
    void shouldJoinAMessageSplitAcrossDataLines() {
        String stream =
                """
                data: {"jsonrpc":"2.0",
                data: "id":"7","result":{}}

                """;

        assertThat(SseFrames.read(body(stream), "7", "acme"))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\n\"id\":\"7\",\"result\":{}}");
    }

    @Test
    void shouldWaitPastAPartialResultForTheCompleteOne() {
        String stream =
                """
                data: {"jsonrpc":"2.0","id":"7","result":{"resultType":"partial"}}

                data: {"jsonrpc":"2.0","id":"7","result":{"resultType":"complete","n":2}}

                """;

        assertThat(SseFrames.read(body(stream), "7", "acme")).contains("\"n\":2");
    }

    @Test
    void shouldFailAnAnswerThatNeverCompleted() {
        String stream =
                """
                data: {"jsonrpc":"2.0","id":"7","result":{"resultType":"partial"}}

                """;

        assertThatThrownBy(() -> SseFrames.read(body(stream), "7", "acme"))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("before the result was complete");
    }

    @Test
    void shouldLeaveAStreamWithNoAnswerToTheCaller() {
        String stream =
                """
                data: {"jsonrpc":"2.0","id":"6","result":{}}

                """;

        assertThat(SseFrames.read(body(stream), "7", "acme")).isEmpty();
    }

    private static InputStream body(String stream) {
        return new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8));
    }
}
