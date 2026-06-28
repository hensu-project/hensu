package io.hensu.core.execution.enricher;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.executor.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FeedbackContextInjector")
class FeedbackContextInjectorTest extends EnricherTestBase {

    private FeedbackContextInjector injector;

    @BeforeEach
    void setUp() {
        injector = new FeedbackContextInjector();
    }

    @Test
    @DisplayName("appends feedback section with heading when recommendation is present")
    void shouldAppendFeedbackWhenRecommendationPresent() {
        ExecutionContext ctx = ctx(null, null);
        ctx.getState().getContext().put(EngineVariables.RECOMMENDATION, "Needs more examples.");

        String result = injector.inject("Write about AI.", minimalNode(), ctx);

        assertThat(result)
                .startsWith("Write about AI.")
                .contains(FeedbackContextInjector.FEEDBACK_SECTION_PREFIX)
                .endsWith("Needs more examples.");
    }

    @Test
    @DisplayName(
            "does not remove recommendation from context — cleanup is TransitionPostProcessor's job")
    void shouldNotRemoveRecommendationFromContext() {
        ExecutionContext ctx = ctx(null, null);
        ctx.getState().getContext().put(EngineVariables.RECOMMENDATION, "Fix structure.");

        injector.inject("prompt", minimalNode(), ctx);

        assertThat(ctx.getState().getContext()).containsKey(EngineVariables.RECOMMENDATION);
    }

    @Test
    @DisplayName(
            "feedback injector is first in DEFAULT pipeline — agent sees feedback before format instructions")
    void shouldBeFirstInDefaultPipeline() {
        ExecutionContext ctx = ctx(null, null);
        ctx.getState().getContext().put(EngineVariables.RECOMMENDATION, "Fix structure.");

        String result = EngineVariablePromptEnricher.DEFAULT.enrich("base", minimalNode(), ctx);

        int feedbackPos = result.indexOf("### Previous Feedback");
        assertThat(feedbackPos).as("feedback section must be present").isGreaterThan(0);

        assertThat(result.substring(0, feedbackPos).trim())
                .as("nothing between base prompt and feedback section")
                .isEqualTo("base");

        // Recommendation still in context — cleanup is TransitionPostProcessor's job
        assertThat(ctx.getState().getContext()).containsKey(EngineVariables.RECOMMENDATION);
    }

    @Test
    @DisplayName("no recommendation in context — prompt returned unchanged")
    void shouldReturnPromptUnchangedWhenNoRecommendation() {
        ExecutionContext ctx = ctx(null, null);

        String result = injector.inject("Write about AI.", minimalNode(), ctx);

        assertThat(result).isEqualTo("Write about AI.");
    }

    @Test
    @DisplayName("blank recommendation — prompt returned unchanged")
    void shouldReturnPromptUnchangedWhenRecommendationBlank() {
        ExecutionContext ctx = ctx(null, null);
        ctx.getState().getContext().put(EngineVariables.RECOMMENDATION, "   ");

        String result = injector.inject("Write about AI.", minimalNode(), ctx);

        assertThat(result).isEqualTo("Write about AI.");
    }

    @Test
    @DisplayName("non-String recommendation — prompt returned unchanged")
    void shouldReturnPromptUnchangedWhenRecommendationNotString() {
        ExecutionContext ctx = ctx(null, null);
        ctx.getState().getContext().put(EngineVariables.RECOMMENDATION, 42);

        String result = injector.inject("Write about AI.", minimalNode(), ctx);

        assertThat(result).isEqualTo("Write about AI.");
    }
}
