package io.hensu.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.tool.ToolDefinition.ParameterDef;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ToolDefinitionTest {

    @Nested
    class ConstructorValidation {

        @Test
        void shouldThrowWhenNameIsNull() {
            assertThatThrownBy(() -> new ToolDefinition(null, "desc", List.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void shouldThrowWhenNameIsBlank() {
            assertThatThrownBy(() -> new ToolDefinition("  ", "desc", List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void shouldThrowWhenDescriptionIsNull() {
            assertThatThrownBy(() -> new ToolDefinition("tool", null, List.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("description");
        }

        @Test
        void shouldDefaultParametersToEmptyList() {
            ToolDefinition tool = new ToolDefinition("tool", "desc", null, null);

            assertThat(tool.parameters()).isNotNull().isEmpty();
        }

        @Test
        void shouldMakeDefensiveCopyOfParameters() {
            List<ParameterDef> originalParams =
                    new java.util.ArrayList<>(
                            List.of(ParameterDef.required("query", "string", "Search query")));

            ToolDefinition tool = new ToolDefinition("search", "Search", originalParams, null);

            originalParams.clear();

            assertThat(tool.parameters()).hasSize(1);
        }
    }

    @Nested
    class StaticFactoryMethods {

        @Test
        void shouldCreateToolWithOf() {
            List<ParameterDef> params = List.of(ParameterDef.required("query", "string", "Query"));

            ToolDefinition tool = ToolDefinition.of("search", "Search tool", params);

            assertThat(tool.name()).isEqualTo("search");
            assertThat(tool.description()).isEqualTo("Search tool");
            assertThat(tool.parameters()).hasSize(1);
            assertThat(tool.returnType()).isNull();
        }

        @Test
        void shouldCreateSimpleTool() {
            ToolDefinition tool = ToolDefinition.simple("ping", "Ping service");

            assertThat(tool.name()).isEqualTo("ping");
            assertThat(tool.description()).isEqualTo("Ping service");
            assertThat(tool.parameters()).isEmpty();
            assertThat(tool.returnType()).isNull();
        }
    }

    @Nested
    class RequiredParameters {

        @Test
        void shouldDetectRequiredParameters() {
            ToolDefinition withRequired =
                    new ToolDefinition(
                            "tool",
                            "desc",
                            List.of(ParameterDef.required("param", "string", "Param")),
                            null);

            ToolDefinition withOptional =
                    new ToolDefinition(
                            "tool",
                            "desc",
                            List.of(ParameterDef.optional("param", "string", "Param", "default")),
                            null);

            ToolDefinition empty = ToolDefinition.simple("tool", "desc");

            assertThat(withRequired.hasRequiredParameters()).isTrue();
            assertThat(withOptional.hasRequiredParameters()).isFalse();
            assertThat(empty.hasRequiredParameters()).isFalse();
        }

        @Test
        void shouldListRequiredParameterNames() {
            ToolDefinition tool =
                    new ToolDefinition(
                            "tool",
                            "desc",
                            List.of(
                                    ParameterDef.required("required1", "string", "Req 1"),
                                    ParameterDef.optional("optional", "string", "Opt", null),
                                    ParameterDef.required("required2", "number", "Req 2")),
                            null);

            assertThat(tool.requiredParameterNames()).containsExactly("required1", "required2");
        }
    }

    @Nested
    class ParameterDefTest {

        @Test
        void shouldThrowWhenParameterNameIsNull() {
            assertThatThrownBy(() -> new ParameterDef(null, "string", "desc", true, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void shouldThrowWhenParameterTypeIsNull() {
            assertThatThrownBy(() -> new ParameterDef("name", null, "desc", true, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("type");
        }

        @Test
        void shouldThrowWhenParameterDescriptionIsNull() {
            assertThatThrownBy(() -> new ParameterDef("name", "string", null, true, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("description");
        }

        @Test
        void shouldCreateRequiredParameter() {
            ParameterDef param = ParameterDef.required("query", "string", "Search query");

            assertThat(param.name()).isEqualTo("query");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.description()).isEqualTo("Search query");
            assertThat(param.required()).isTrue();
            assertThat(param.defaultValue()).isNull();
        }

        @Test
        void shouldCreateOptionalParameter() {
            ParameterDef param = ParameterDef.optional("limit", "number", "Max results", 10);

            assertThat(param.name()).isEqualTo("limit");
            assertThat(param.type()).isEqualTo("number");
            assertThat(param.description()).isEqualTo("Max results");
            assertThat(param.required()).isFalse();
            assertThat(param.defaultValue()).isEqualTo(10);
        }
    }
}
