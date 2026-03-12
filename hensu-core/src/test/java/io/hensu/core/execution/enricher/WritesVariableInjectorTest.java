package io.hensu.core.execution.enricher;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.state.StateVariableDeclaration;
import io.hensu.core.workflow.state.VarType;
import io.hensu.core.workflow.state.WorkflowStateSchema;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WritesVariableInjector")
class WritesVariableInjectorTest extends EnricherTestBase {

    private WritesVariableInjector injector;

    @BeforeEach
    void setUp() {
        injector = new WritesVariableInjector();
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns prompt unchanged when node is not a StandardNode")
        void shouldSkipNonStandardNode() {
            var end = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

            assertThat(injector.inject("base", end, ctx(Map.of(), null, null))).isEqualTo("base");
        }

        @Test
        @DisplayName("returns prompt unchanged when writes list is empty")
        void shouldSkipWhenWritesEmpty() {
            var node =
                    StandardNode.builder()
                            .id("node")
                            .writes(List.of())
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            assertThat(injector.inject("base", node, ctx(Map.of(), null, null))).isEqualTo("base");
        }
    }

    @Nested
    @DisplayName("field injection")
    class FieldInjection {

        @Test
        @DisplayName("emits field name only when workflow has no state schema")
        void shouldEmitNameOnlyWithoutSchema() {
            String result = injector.inject("base", nodeWithWrites(), ctx(Map.of(), null, null));

            assertThat(result).contains("\"article\"");
            assertThat(result).doesNotContain(" — ");
        }

        @Test
        @DisplayName("appends description when schema declares one for the field")
        void shouldAppendDescriptionFromSchema() {
            var schema =
                    new WorkflowStateSchema(
                            List.of(
                                    new StateVariableDeclaration(
                                            "article",
                                            VarType.STRING,
                                            false,
                                            "the full article text")));

            String result = injector.inject("base", nodeWithWrites(), ctx(Map.of(), schema, null));

            assertThat(result).contains("\"article\" — the full article text");
        }

        @Test
        @DisplayName("emits field name only when schema entry has no description")
        void shouldEmitNameOnlyWhenDescriptionNull() {
            // 3-arg constructor → description() returns null → descriptionOf() returns
            // Optional.empty()
            var schema =
                    new WorkflowStateSchema(
                            List.of(
                                    new StateVariableDeclaration(
                                            "article", VarType.STRING, false)));

            String result = injector.inject("base", nodeWithWrites(), ctx(Map.of(), schema, null));

            assertThat(result).contains("\"article\"");
            assertThat(result).doesNotContain(" — ");
        }

        @Test
        @DisplayName("emits field name only when schema exists but field is not declared in it")
        void shouldEmitNameOnlyWhenFieldNotInSchema() {
            // Schema declares "summary" — node writes "article" which is absent from schema.
            // descriptionOf("article") returns Optional.empty(); no description must be injected.
            var schema =
                    new WorkflowStateSchema(
                            List.of(
                                    new StateVariableDeclaration(
                                            "summary", VarType.STRING, false)));

            String result = injector.inject("base", nodeWithWrites(), ctx(Map.of(), schema, null));

            assertThat(result).contains("\"article\"");
            assertThat(result).doesNotContain(" — ");
        }
    }

    // — Helpers ——————————————————————————————————————————————————————————————

    private StandardNode nodeWithWrites() {
        return StandardNode.builder()
                .id("node")
                .writes(List.of(new String[] {"article"}))
                .transitionRules(List.of(new SuccessTransition("next")))
                .build();
    }
}
