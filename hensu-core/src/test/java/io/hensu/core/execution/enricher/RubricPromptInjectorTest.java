package io.hensu.core.execution.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.rubric.InMemoryRubricRepository;
import io.hensu.core.rubric.RubricEngine;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RubricPromptInjector")
class RubricPromptInjectorTest extends EnricherTestBase {

    private RubricPromptInjector injector;
    private RubricEngine rubricEngine;

    @BeforeEach
    void setUp() {
        injector = new RubricPromptInjector();
        rubricEngine = new RubricEngine(new InMemoryRubricRepository(), (_, _, _) -> 0.0);
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns prompt unchanged when node is not a StandardNode")
        void shouldSkipNonStandardNode() {
            var end = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

            assertThat(injector.inject("base prompt", end, ctx(Map.of(), null, rubricEngine)))
                    .isEqualTo("base prompt");
        }

        @Test
        @DisplayName("returns prompt unchanged when rubricId is null")
        void shouldSkipWhenNoRubricId() {
            var node =
                    StandardNode.builder()
                            .id("node")
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            assertThat(injector.inject("base prompt", node, ctx(Map.of(), null, rubricEngine)))
                    .isEqualTo("base prompt");
        }
    }

    @Nested
    @DisplayName("rubric loading")
    class RubricLoading {

        @Test
        @DisplayName("uses engine cache — does not attempt disk read when rubric is registered")
        void shouldUseCachedRubric() {
            rubricEngine.registerRubric(rubric("q", "Quality", null));
            var node =
                    StandardNode.builder()
                            .id("node")
                            .rubricId("q")
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();
            // empty rubric path map — disk attempt would throw
            assertThat(injector.inject("prompt", node, ctx(Map.of(), null, rubricEngine)))
                    .contains("Score the content above");
        }

        @Test
        @DisplayName(
                "throws IllegalStateException with rubricId in message when path not configured")
        void shouldThrowWhenPathMissing() {
            var node =
                    StandardNode.builder()
                            .id("node")
                            .rubricId("unknown-rubric")
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            assertThatThrownBy(
                            () ->
                                    injector.inject(
                                            "prompt", node, ctx(Map.of(), null, rubricEngine)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown-rubric");
        }
    }

    @Nested
    @DisplayName("criteria formatting")
    class CriteriaFormatting {

        @Test
        @DisplayName("formats criterion with description as `**name** — description?`")
        void shouldFormatCriterionWithDescription() {
            String result = injector.inject("base", rubric("r", "Structure", "Is it organized"));

            assertThat(result).contains("- **Structure** — Is it organized?");
        }

        @Test
        @DisplayName("omits em-dash when criterion description is blank")
        void shouldOmitDashWhenDescriptionBlank() {
            String result = injector.inject("base", rubric("r", "Clarity", ""));

            assertThat(result).contains("- **Clarity**");
            assertThat(result).doesNotContain(" — ");
        }

        @Test
        @DisplayName("omits em-dash when criterion description is null")
        void shouldOmitDashWhenDescriptionNull() {
            // Criterion.Builder defaults description to "" but explicit null is permitted.
            // The null guard in buildCriteriaSection must fire before isBlank() to avoid NPE.
            Rubric rubric =
                    Rubric.builder()
                            .id("r")
                            .name("Test")
                            .criteria(
                                    List.of(
                                            Criterion.builder()
                                                    .id("c1")
                                                    .name("Clarity")
                                                    .description(null)
                                                    .build()))
                            .build();

            String result = injector.inject("base", rubric);

            assertThat(result).contains("- **Clarity**");
            assertThat(result).doesNotContain(" — ");
        }
    }

    // — Helpers ——————————————————————————————————————————————————————————————

    private Rubric rubric(String id, String criterionName, String description) {
        Criterion.Builder cb = Criterion.builder().id("c1").name(criterionName);
        if (description != null) {
            cb.description(description);
        }
        return Rubric.builder().id(id).name("Test").criteria(List.of(cb.build())).build();
    }
}
