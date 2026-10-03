package io.hensu.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.mcp.McpParameterHeaders.Accepted;
import io.hensu.mcp.McpParameterHeaders.Mirror;
import io.hensu.mcp.McpParameterHeaders.Rejected;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/// Holds the `x-mcp-header` rules to the letter of revision 2026-07-28.
///
/// Each constraint here is one a server can plausibly break, and each one the
/// client gets wrong costs something different: accepting an invalid tool sends
/// a header the server rejects on every call, and rejecting a valid one hides a
/// tool the operator declared.
class McpParameterHeadersTest {

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    private static Map<String, Object> annotated(String type, String header) {
        return Map.of("type", type, "x-mcp-header", header);
    }

    @Nested
    class Reading {

        @Test
        void shouldFindAnnotationsAtEveryDepthReachedThroughPropertiesAlone() {
            var schema =
                    object(
                            Map.of(
                                    "region", annotated("string", "Region"),
                                    "target",
                                            object(
                                                    Map.of(
                                                            "shard",
                                                            annotated("integer", "Shard")))));

            var read = McpParameterHeaders.read(schema);

            assertThat(read)
                    .isInstanceOfSatisfying(
                            Accepted.class,
                            accepted ->
                                    assertThat(accepted.mirrors())
                                            .containsExactlyInAnyOrder(
                                                    new Mirror("Region", List.of("region")),
                                                    new Mirror(
                                                            "Shard", List.of("target", "shard"))));
        }

        @Test
        void shouldAcceptANullablePrimitive() {
            // A null value sends no header, so "string or null" still has a
            // header form whenever there is a value to send.
            var schema =
                    object(
                            Map.of(
                                    "tenant",
                                    Map.of(
                                            "type",
                                            List.of("string", "null"),
                                            "x-mcp-header",
                                            "Tenant")));

            assertThat(McpParameterHeaders.read(schema)).isInstanceOf(Accepted.class);
        }

        @Test
        void shouldNotMistakeAPropertyOrInstanceDataSpelledLikeTheKeywordForAnAnnotation() {
            var schema =
                    object(
                            Map.of(
                                    "x-mcp-header",
                                    Map.of("type", "string"),
                                    "choice",
                                    Map.of(
                                            "type",
                                            "object",
                                            "default",
                                            Map.of("x-mcp-header", "Nope"),
                                            "enum",
                                            List.of(Map.of("x-mcp-header", "Nope")))));

            assertThat(McpParameterHeaders.read(schema)).isEqualTo(Accepted.NONE);
        }

        static Stream<Arguments> invalidSchemas() {
            return Stream.of(
                    Arguments.of(
                            "number is excluded",
                            object(Map.of("ratio", annotated("number", "Ratio"))),
                            "type is number"),
                    Arguments.of(
                            "object has no header form",
                            object(Map.of("filter", annotated("object", "Filter"))),
                            "type is object"),
                    Arguments.of(
                            "an undeclared type is not a primitive",
                            object(Map.of("any", Map.of("x-mcp-header", "Any"))),
                            "type is undeclared"),
                    Arguments.of(
                            "empty name",
                            object(Map.of("region", annotated("string", ""))),
                            "is empty"),
                    Arguments.of(
                            "space is not a tchar",
                            object(Map.of("region", annotated("string", "Cloud Region"))),
                            "' ' is not allowed"),
                    Arguments.of(
                            "colon is not a tchar",
                            object(Map.of("region", annotated("string", "Region:"))),
                            "':' is not allowed"),
                    Arguments.of(
                            "CRLF would split the header block",
                            object(Map.of("region", annotated("string", "Region\r\nX-Evil"))),
                            "control character"),
                    Arguments.of(
                            "not a string",
                            object(Map.of("region", Map.of("type", "string", "x-mcp-header", 7))),
                            "is not a string"),
                    Arguments.of(
                            "names collide case-insensitively",
                            object(
                                    Map.of(
                                            "a", annotated("string", "Region"),
                                            "b", annotated("string", "REGION"))),
                            "more than one property"),
                    Arguments.of(
                            "under items",
                            object(
                                    Map.of(
                                            "regions",
                                            Map.of(
                                                    "type",
                                                    "array",
                                                    "items",
                                                    annotated("string", "Region")))),
                            "under 'items'"),
                    Arguments.of(
                            "under a composition keyword",
                            Map.of(
                                    "type",
                                    "object",
                                    "anyOf",
                                    List.of(object(Map.of("r", annotated("string", "Region"))))),
                            "under 'anyOf'"),
                    Arguments.of(
                            "behind a $ref target",
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("r", Map.of("$ref", "#/$defs/region")),
                                    "$defs",
                                    Map.of("region", annotated("string", "Region"))),
                            "under '$defs'"),
                    Arguments.of(
                            "on the root",
                            Map.of("type", "object", "x-mcp-header", "Root"),
                            "schema root"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidSchemas")
        void shouldRejectATool(String why, Map<String, Object> schema, String reason) {
            assertThat(McpParameterHeaders.read(schema))
                    .isInstanceOfSatisfying(
                            Rejected.class,
                            rejected -> assertThat(rejected.reason()).contains(reason));
        }
    }

    @Nested
    class Encoding {

        /// The rows of the spec's own encoding table, plus the bare-tab choice.
        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "us-west1|us-west1",
                    "Hello, 世界|=?base64?SGVsbG8sIOS4lueVjA==?=",
                    "' padded '|=?base64?IHBhZGRlZCA=?=",
                    "=?base64?literal?=|=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=",
                })
        void shouldEncodeExactlyAsTheSpecTableDoes(String value, String header) {
            assertThat(McpParameterHeaders.encode(value)).isEqualTo(header);
        }

        @Test
        void shouldEncodeAValueThatWouldSplitTheHeaderBlock() {
            assertThat(McpParameterHeaders.encode("line1\nline2"))
                    .isEqualTo("=?base64?bGluZTEKbGluZTI=?=");
        }
    }

    @Nested
    class Extracting {

        private final List<Mirror> mirrors =
                List.of(
                        new Mirror("Region", List.of("region")),
                        new Mirror("Shard", List.of("target", "shard")),
                        new Mirror("Dry", List.of("dry")));

        @Test
        void shouldOmitTheHeaderForAnAbsentOrNullArgument() {
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("region", null);

            assertThat(McpParameterHeaders.headers(mirrors, arguments)).isEmpty();
        }

        @Test
        void shouldReadNestedValuesAndRenderEachPrimitiveTheSpecsWay() {
            var headers =
                    McpParameterHeaders.headers(
                            mirrors,
                            Map.of(
                                    "region",
                                    "eu",
                                    "target",
                                    Map.of("shard", 42.0),
                                    "dry",
                                    Boolean.FALSE));

            assertThat(headers)
                    .containsExactly(
                            Map.entry("Mcp-Param-Region", "eu"),
                            Map.entry("Mcp-Param-Shard", "42"),
                            Map.entry("Mcp-Param-Dry", "false"));
        }

        static Stream<Arguments> unmirrorable() {
            return Stream.of(
                    Arguments.of(List.of("eu", "us"), "a list"),
                    Arguments.of(Map.of("name", "eu"), "an object"),
                    Arguments.of(3.5, "the number 3.5"),
                    Arguments.of(
                            new BigDecimal(McpParameterHeaders.MAX_SAFE_INTEGER)
                                    .add(BigDecimal.ONE),
                            "not an integer within"));
        }

        @ParameterizedTest
        @MethodSource("unmirrorable")
        void shouldRefuseAnArgumentWithNoHeaderForm(Object value, String described) {
            assertThatThrownBy(() -> McpParameterHeaders.headers(mirrors, Map.of("region", value)))
                    .isInstanceOf(McpInvalidArgumentException.class)
                    .hasMessageContaining("'region'")
                    .hasMessageContaining("Mcp-Param-Region")
                    .hasMessageContaining(described);
        }
    }
}
