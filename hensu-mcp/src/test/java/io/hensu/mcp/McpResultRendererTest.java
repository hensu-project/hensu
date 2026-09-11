package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpResultRendererTest {

    @Nested
    class TextContent {

        /// MCP splits a single answer across several blocks; joining them out of
        /// order, or dropping any of them, silently corrupts what the agent reads.
        @Test
        void shouldConcatenateTextBlocksInOrder() {
            Map<String, Object> response =
                    Map.of(
                            "content",
                            List.of(
                                    Map.of("type", "text", "text", "first"),
                                    Map.of("type", "text", "text", "second"),
                                    Map.of("type", "text", "text", "third")));

            assertThat(McpResultRenderer.render(response)).isEqualTo("first\nsecond\nthird");
        }

        @Test
        void shouldRenderEmptyContentAsEmptyText() {
            assertThat(McpResultRenderer.render(Map.of("content", List.of()))).isEmpty();
            assertThat(McpResultRenderer.render(Map.of())).isEmpty();
            assertThat(McpResultRenderer.render(null)).isEmpty();
        }
    }

    @Nested
    class NonTextContent {

        /// Base64 image bytes in a context window are waste; the agent needs to know
        /// a block came back and what it was, not what it contained.
        @Test
        void shouldRenderImageBlockAsTypedPlaceholder() {
            Map<String, Object> response =
                    Map.of(
                            "content",
                            List.of(
                                    Map.of(
                                            "type",
                                            "image",
                                            "data",
                                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB",
                                            "mimeType",
                                            "image/png")));

            String rendered = McpResultRenderer.render(response);

            assertThat(rendered).isEqualTo("[image: image/png]");
            assertThat(rendered).doesNotContain("iVBORw0KGgo");
        }

        @Test
        void shouldRenderEmbeddedResourceWithUriAndMimeType() {
            Map<String, Object> response =
                    Map.of(
                            "content",
                            List.of(
                                    Map.of(
                                            "type",
                                            "resource",
                                            "resource",
                                            Map.of(
                                                    "uri",
                                                    "file:///etc/hosts",
                                                    "mimeType",
                                                    "text/plain"))));

            assertThat(McpResultRenderer.render(response))
                    .isEqualTo("[resource: file:///etc/hosts (text/plain)]");
        }

        @Test
        void shouldMixTextAndPlaceholders() {
            Map<String, Object> response =
                    Map.of(
                            "content",
                            List.of(
                                    Map.of("type", "text", "text", "here is the chart"),
                                    Map.of("type", "image", "mimeType", "image/svg+xml")));

            assertThat(McpResultRenderer.render(response))
                    .isEqualTo("here is the chart\n[image: image/svg+xml]");
        }
    }

    @Nested
    class Fallback {

        /// The bug this guards: handing the raw map to the model produced a Java
        /// `Map.toString` – braces, equals signs, no quoting – in the context window.
        @Test
        void shouldFallBackToJsonForUnrecognizedPayload() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("structuredContent", Map.of("rows", 7));

            String rendered = McpResultRenderer.render(response);

            assertThat(rendered).isEqualTo("{\"structuredContent\":{\"rows\":7}}");
            assertThat(rendered).doesNotContain("=");
        }
    }

    @Nested
    class ErrorMapping {

        @Test
        void shouldMapIsErrorOntoFailureCarryingTheText() {
            Map<String, Object> response =
                    Map.of(
                            "content",
                            List.of(Map.of("type", "text", "text", "file not found")),
                            "isError",
                            true);

            ToolCallResult result = McpResultRenderer.toResult("read_file", response);

            assertThat(result.status()).isEqualTo(ToolCallStatus.FAILURE);
            assertThat(result.error()).isEqualTo("file not found");
            assertThat(result.success()).isFalse();
        }

        @Test
        void shouldMapAbsentIsErrorOntoSuccess() {
            Map<String, Object> response =
                    Map.of("content", List.of(Map.of("type", "text", "text", "ok")));

            ToolCallResult result = McpResultRenderer.toResult("read_file", response);

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).isEqualTo("ok");
        }
    }
}
