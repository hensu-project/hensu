package io.hensu.core.execution.enricher;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.result.ExitStatus;
import io.hensu.core.rubric.RubricParser;
import io.hensu.core.rubric.model.Criterion;
import io.hensu.core.rubric.model.Rubric;
import io.hensu.core.workflow.WorkflowTest;
import io.hensu.core.workflow.node.EndNode;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RubricPromptInjector")
class RubricPromptInjectorTest extends EnricherTestBase {

    private RubricPromptInjector injector;

    @BeforeEach
    void setUp() {
        injector = new RubricPromptInjector();
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns prompt unchanged when node is not a StandardNode")
        void shouldSkipNonStandardNode() {
            var end = EndNode.builder().id("end").status(ExitStatus.SUCCESS).build();

            assertThat(injector.inject("base prompt", end, ctx(null, null)))
                    .isEqualTo("base prompt");
        }

        @Test
        @DisplayName("returns prompt unchanged when rubric is null")
        void shouldSkipWhenNoRubric() {
            var node =
                    StandardNode.builder()
                            .id("node")
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            assertThat(injector.inject("base prompt", node, ctx(null, null)))
                    .isEqualTo("base prompt");
        }
    }

    @Nested
    @DisplayName("rubric loading")
    class RubricLoading {

        private static final String RUBRIC_CONTENT =
                WorkflowTest.TestWorkflowBuilder.RUBRIC_CONTENT;

        @Test
        @DisplayName("parses rubric content from node and injects criteria")
        void shouldParseRubricContent() {
            var node =
                    StandardNode.builder()
                            .id("node")
                            .rubric(RubricParser.parseContent("node", RUBRIC_CONTENT))
                            .transitionRules(List.of(new SuccessTransition("next")))
                            .build();

            assertThat(injector.inject("prompt", node, ctx(null, null)))
                    .contains("Score the content above");
        }
    }

    @Nested
    @DisplayName("criteria formatting")
    class CriteriaFormatting {

        @Test
        @DisplayName("formats criterion with description as `**name** — description?`")
        void shouldFormatCriterionWithDescription() {
            String result = injector.inject("base", rubric("Structure", "Is it organized"));

            assertThat(result).contains("- **Structure** — Is it organized?");
        }

        @Test
        @DisplayName("omits em-dash when criterion description is blank")
        void shouldOmitDashWhenDescriptionBlank() {
            String result = injector.inject("base", rubric("Clarity", ""));

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

    private Rubric rubric(String criterionName, String description) {
        Criterion.Builder cb = Criterion.builder().id("c1").name(criterionName);
        if (description != null) {
            cb.description(description);
        }
        return Rubric.builder().id("r").name("Test").criteria(List.of(cb.build())).build();
    }
}
