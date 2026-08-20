package io.hensu.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hensu.core.resume.ResumeInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Verifies that a resume body carrying unrecognized fields is caught, not silently dropped.
///
/// The rejection in {@link ResumeRequest#toResumeInput()} only fires if Jackson actually routes
/// unmatched properties into the `unknownFields` component, which depends on `@JsonAnySetter`
/// being honoured on a record component. If that binding ever stops working the map stays empty,
/// the check becomes a no-op, and `{"approved": true}` degrades back into
/// {@link ResumeInput#NONE} – the endless review re-prompt this guard exists to prevent. These
/// tests deserialize real JSON so that regression fails here instead of in production.
@DisplayName("ResumeRequest deserialization")
class ResumeRequestDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("an unrecognized field is captured and named in the rejection")
    void unknownFieldIsCapturedAndRejected() throws Exception {
        ResumeRequest request = mapper.readValue("{\"approved\":true}", ResumeRequest.class);

        assertThat(request.unknownFields()).containsKey("approved");
        assertThatThrownBy(request::toResumeInput)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
    }

    @Test
    @DisplayName("a review-shaped body spelled wrongly never degrades to a plain resume")
    void malformedReviewBodyDoesNotDegradeToNone() throws Exception {
        ResumeRequest request =
                mapper.readValue("{\"approved\":true,\"modifications\":{}}", ResumeRequest.class);

        assertThatThrownBy(request::toResumeInput)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved")
                .hasMessageContaining("modifications");
    }

    @Test
    @DisplayName("an unrecognized field with a null value is still captured, not an NPE")
    void unknownFieldWithNullValueIsCaptured() throws Exception {
        ResumeRequest request = mapper.readValue("{\"approved\":null}", ResumeRequest.class);

        assertThat(request.unknownFields()).containsKey("approved");
        assertThatThrownBy(request::toResumeInput)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
    }

    @Test
    @DisplayName("a well-formed decision deserializes with no unknown fields")
    void wellFormedDecisionCarriesNoUnknownFields() throws Exception {
        ResumeRequest request =
                mapper.readValue(
                        "{\"decision\":\"approve\",\"correlationId\":\"corr-1\"}",
                        ResumeRequest.class);

        assertThat(request.unknownFields()).isEmpty();
        assertThat(request.toResumeInput()).isInstanceOf(ResumeInput.ApplyReview.class);
    }

    @Test
    @DisplayName("an empty body still means a plain resume")
    void emptyBodyIsPlainResume() throws Exception {
        ResumeRequest request = mapper.readValue("{}", ResumeRequest.class);

        assertThat(request.toResumeInput()).isEqualTo(ResumeInput.NONE);
    }
}
