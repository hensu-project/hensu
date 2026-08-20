package io.hensu.core.state;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.resume.ResumeInput;
import io.hensu.core.review.ReviewDecision;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies that a resume input and the persisted phase must agree.
///
/// An execution paused at a review gate has nothing to resume *with* except a decision.
/// Accepting anything else re-entered the post-pipeline, hit the review processor again, and
/// requested a fresh review — a silent loop that answered every malformed call with HTTP 200.
@DisplayName("ExecutionPhase.validateCorrelation")
class ExecutionPhaseValidationTest {

    private static final ExecutionPhase AWAITING =
            new ExecutionPhase.Awaiting(
                    "review",
                    "ReviewPostProcessor",
                    new NodeResult(ResultStatus.SUCCESS, "draft", Map.of()),
                    "corr-1",
                    Instant.now());

    @Test
    @DisplayName("rejects a plain resume of an execution awaiting a review decision")
    void rejectsPlainResumeWhileAwaiting() {
        assertThatThrownBy(() -> ExecutionPhase.validateCorrelation(AWAITING, ResumeInput.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corr-1")
                .hasMessageContaining("approve");
    }

    @Test
    @DisplayName("rejects context edits as a substitute for a review decision")
    void rejectsContextEditsWhileAwaiting() {
        var edits = new ResumeInput.ApplyContextEdits(Map.of("limit", 1));

        assertThatThrownBy(() -> ExecutionPhase.validateCorrelation(AWAITING, edits))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("accepts a review decision carrying the matching correlation id")
    void acceptsMatchingDecision() {
        var review = new ResumeInput.ApplyReview("corr-1", new ReviewDecision.Approve(null));

        assertThatCode(() -> ExecutionPhase.validateCorrelation(AWAITING, review))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a review decision whose correlation id names a different pause")
    void rejectsMismatchedCorrelation() {
        var review = new ResumeInput.ApplyReview("corr-other", new ReviewDecision.Approve(null));

        assertThatThrownBy(() -> ExecutionPhase.validateCorrelation(AWAITING, review))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Correlation id mismatch");
    }

    @Test
    @DisplayName("a plain resume of a fresh execution is untouched")
    void allowsPlainResumeWhenNotAwaiting() {
        assertThatCode(
                        () ->
                                ExecutionPhase.validateCorrelation(
                                        ExecutionPhase.INITIAL, ResumeInput.NONE))
                .doesNotThrowAnyException();
    }
}
