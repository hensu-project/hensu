package io.hensu.adapter.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Verifies what a model is actually told about a tool's inputs.
///
/// The flat {@link ParameterDef} list cannot express an enumeration or an
/// array's item type, so a discovered schema that carries one has to survive the
/// translation or the constraint reaches nobody.
class LangChain4jToolSpecTest {

    @Nested
    class DiscoveredSchema {

        @Test
        void shouldOfferAnEnumerationAsChoicesRatherThanAnOpenString() {
            ToolDefinition tool =
                    new ToolDefinition(
                            "search",
                            "Searches",
                            List.of(ParameterDef.required("mode", "string", "How to search")),
                            null,
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of("mode", Map.of("enum", List.of("fast", "thorough"))),
                                    "required",
                                    List.of("mode")));

            ToolSpecification spec = LangChain4jToolSession.toToolSpec(tool);

            assertThat(spec.parameters().properties().get("mode"))
                    .isInstanceOfSatisfying(
                            JsonEnumSchema.class,
                            schema ->
                                    assertThat(schema.enumValues())
                                            .containsExactly("fast", "thorough"));
            assertThat(spec.parameters().required()).containsExactly("mode");
        }

        @Test
        void shouldKeepAnArraysItemType() {
            ToolDefinition tool =
                    new ToolDefinition(
                            "tag",
                            "Tags",
                            List.of(),
                            null,
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of(
                                            "ids",
                                            Map.of(
                                                    "type",
                                                    "array",
                                                    "items",
                                                    Map.of("type", "integer")))));

            ToolSpecification spec = LangChain4jToolSession.toToolSpec(tool);

            assertThat(spec.parameters().properties().get("ids"))
                    .isInstanceOfSatisfying(
                            JsonArraySchema.class,
                            schema ->
                                    assertThat(schema.items())
                                            .isInstanceOf(JsonIntegerSchema.class));
        }
    }

    @Nested
    class LocallyDeclaredTool {

        @Test
        void shouldRebuildTheSchemaFromTheParameterList() {
            ToolDefinition tool =
                    ToolDefinition.of(
                            "write_file",
                            "Writes a file",
                            List.of(
                                    ParameterDef.required("path", "string", "Where"),
                                    ParameterDef.optional("mode", "integer", "Permissions", 420)));

            ToolSpecification spec = LangChain4jToolSession.toToolSpec(tool);

            assertThat(spec.parameters().properties())
                    .hasEntrySatisfying(
                            "path",
                            schema -> assertThat(schema).isInstanceOf(JsonStringSchema.class))
                    .hasEntrySatisfying(
                            "mode",
                            schema -> assertThat(schema).isInstanceOf(JsonIntegerSchema.class));
            assertThat(spec.parameters().required()).containsExactly("path");
        }
    }
}
