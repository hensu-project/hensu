package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.tool.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class McpSchemaConverterTest {

    @Nested
    class Conversion {

        @Test
        void shouldConvertSimpleTool() {
            var mcpTool = new McpConnection.McpToolDescriptor("ping", "Ping a server", Map.of());

            ToolDefinition tool = McpSchemaConverter.convert(mcpTool);

            assertThat(tool.name()).isEqualTo("ping");
            assertThat(tool.description()).isEqualTo("Ping a server");
            assertThat(tool.parameters()).isEmpty();
        }

        @Test
        void shouldConvertToolWithParameters() {
            var mcpTool =
                    new McpConnection.McpToolDescriptor(
                            "fetch",
                            "Fetch a URL",
                            Map.of(
                                    "properties",
                                            Map.of(
                                                    "url",
                                                            Map.of(
                                                                    "type", "string",
                                                                    "description",
                                                                            "The URL to fetch"),
                                                    "timeout",
                                                            Map.of(
                                                                    "type", "number",
                                                                    "description", "Timeout in ms",
                                                                    "default", 5000)),
                                    "required", List.of("url")));

            ToolDefinition tool = McpSchemaConverter.convert(mcpTool);

            assertThat(tool.parameters()).hasSize(2);

            var urlParam =
                    tool.parameters().stream()
                            .filter(p -> p.name().equals("url"))
                            .findFirst()
                            .orElseThrow();
            assertThat(urlParam.type()).isEqualTo("string");
            assertThat(urlParam.description()).isEqualTo("The URL to fetch");
            assertThat(urlParam.required()).isTrue();

            var timeoutParam =
                    tool.parameters().stream()
                            .filter(p -> p.name().equals("timeout"))
                            .findFirst()
                            .orElseThrow();
            assertThat(timeoutParam.type()).isEqualTo("number");
            assertThat(timeoutParam.required()).isFalse();
            assertThat(timeoutParam.defaultValue()).isEqualTo(5000);
        }

        @Test
        void shouldHandleNullInputSchema() {
            var mcpTool = new McpConnection.McpToolDescriptor("simple", "Simple tool", null);

            ToolDefinition tool = McpSchemaConverter.convert(mcpTool);

            assertThat(tool.parameters()).isEmpty();
        }

        @Test
        void shouldHandleMissingRequired() {
            var mcpTool =
                    new McpConnection.McpToolDescriptor(
                            "tool",
                            "desc",
                            Map.of(
                                    "properties",
                                    Map.of(
                                            "param",
                                            Map.of("type", "string", "description", "A param"))));

            ToolDefinition tool = McpSchemaConverter.convert(mcpTool);

            assertThat(tool.parameters()).hasSize(1);
            assertThat(tool.parameters().getFirst().required()).isFalse();
        }

        /// A malformed schema must degrade to "no parameters" rather than throw:
        /// discovery runs over whatever a third-party server publishes, and one bad
        /// descriptor must not take down the whole catalog.
        @Test
        void shouldIgnoreMalformedProperties() {
            var mcpTool =
                    new McpConnection.McpToolDescriptor(
                            "broken", "desc", Map.of("properties", "not-an-object"));

            ToolDefinition tool = McpSchemaConverter.convert(mcpTool);

            assertThat(tool.parameters()).isEmpty();
        }
    }
}
