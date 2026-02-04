package io.hensu.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.agent.AgentResponse.Error;
import io.hensu.core.agent.AgentResponse.Error.ErrorType;
import io.hensu.core.agent.AgentResponse.PlanProposal;
import io.hensu.core.agent.AgentResponse.TextResponse;
import io.hensu.core.agent.AgentResponse.ToolRequest;
import io.hensu.core.plan.PlannedStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AgentResponseTest {

    @Nested
    class TextResponseTest {

        @Test
        void shouldCreateWithContent() {
            TextResponse response = TextResponse.of("Hello, world!");

            assertThat(response.content()).isEqualTo("Hello, world!");
            assertThat(response.metadata()).isEmpty();
            assertThat(response.timestamp()).isNotNull();
        }

        @Test
        void shouldCreateWithContentAndMetadata() {
            Map<String, Object> metadata = Map.of("model", "gpt-4", "tokens", 150);

            TextResponse response = TextResponse.of("Response text", metadata);

            assertThat(response.content()).isEqualTo("Response text");
            assertThat(response.metadata()).containsEntry("model", "gpt-4");
            assertThat(response.metadata()).containsEntry("tokens", 150);
        }

        @Test
        void shouldThrowWhenContentIsNull() {
            assertThatThrownBy(() -> new TextResponse(null, Map.of(), Instant.now()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("content");
        }

        @Test
        void shouldDefaultMetadataToEmptyMap() {
            TextResponse response = new TextResponse("text", null, Instant.now());

            assertThat(response.metadata()).isNotNull().isEmpty();
        }

        @Test
        void shouldMakeMetadataImmutable() {
            TextResponse response = TextResponse.of("text", Map.of("key", "value"));

            assertThat(response.metadata()).isUnmodifiable();
        }
    }

    @Nested
    class ToolRequestTest {

        @Test
        void shouldCreateToolRequest() {
            ToolRequest request = ToolRequest.of("search", Map.of("query", "test"));

            assertThat(request.toolName()).isEqualTo("search");
            assertThat(request.arguments()).containsEntry("query", "test");
            assertThat(request.reasoning()).isEmpty();
            assertThat(request.timestamp()).isNotNull();
        }

        @Test
        void shouldCreateWithReasoning() {
            ToolRequest request =
                    ToolRequest.of("api_call", Map.of("id", "123"), "Need to fetch user data");

            assertThat(request.toolName()).isEqualTo("api_call");
            assertThat(request.reasoning()).isEqualTo("Need to fetch user data");
        }

        @Test
        void shouldThrowWhenToolNameIsNull() {
            assertThatThrownBy(() -> new ToolRequest(null, Map.of(), "", Instant.now()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("toolName");
        }
    }

    @Nested
    class PlanProposalTest {

        @Test
        void shouldCreatePlanProposal() {
            List<PlannedStep> steps =
                    List.of(
                            PlannedStep.simple(0, "search", "Search"),
                            PlannedStep.simple(1, "summarize", "Summarize"));

            PlanProposal proposal = PlanProposal.of(steps, "Will search then summarize");

            assertThat(proposal.steps()).hasSize(2);
            assertThat(proposal.reasoning()).isEqualTo("Will search then summarize");
            assertThat(proposal.timestamp()).isNotNull();
        }

        @Test
        void shouldDefaultStepsToEmptyList() {
            PlanProposal proposal = new PlanProposal(null, "reason", Instant.now());

            assertThat(proposal.steps()).isNotNull().isEmpty();
        }
    }

    @Nested
    class ErrorTest {

        @Test
        void shouldCreateErrorFromException() {
            RuntimeException exception = new RuntimeException("API error");

            Error error = Error.from(exception);

            assertThat(error.message()).isEqualTo("API error");
            assertThat(error.errorType()).isEqualTo(ErrorType.UNKNOWN);
            assertThat(error.cause()).isSameAs(exception);
            assertThat(error.timestamp()).isNotNull();
        }

        @Test
        void shouldCreateErrorWithMessage() {
            Error error = Error.of("Connection failed");

            assertThat(error.message()).isEqualTo("Connection failed");
            assertThat(error.errorType()).isEqualTo(ErrorType.UNKNOWN);
            assertThat(error.cause()).isNull();
        }

        @Test
        void shouldCreateTypedError() {
            Error error = Error.of("Request timed out", ErrorType.TIMEOUT);

            assertThat(error.message()).isEqualTo("Request timed out");
            assertThat(error.errorType()).isEqualTo(ErrorType.TIMEOUT);
        }

        @Test
        void shouldThrowWhenMessageIsNull() {
            assertThatThrownBy(() -> new Error(null, ErrorType.UNKNOWN, null, Instant.now()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
        }

        @Test
        void shouldHandleExceptionWithNullMessage() {
            RuntimeException exception = new RuntimeException((String) null);

            Error error = Error.from(exception);

            assertThat(error.message()).isEqualTo("RuntimeException");
        }
    }

    @Nested
    class PatternMatchingTest {

        @Test
        void shouldSupportExhaustivePatternMatching() {
            AgentResponse textResponse = TextResponse.of("Hello");
            AgentResponse toolRequest = ToolRequest.of("tool", Map.of());
            AgentResponse planProposal = PlanProposal.of(List.of(), "");
            AgentResponse error = Error.of("Error");

            assertThat(extractContent(textResponse)).isEqualTo("Hello");
            assertThat(extractContent(toolRequest)).isEqualTo("Tool: tool");
            assertThat(extractContent(planProposal)).isEqualTo("Plan: 0 steps");
            assertThat(extractContent(error)).isEqualTo("Error: Error");
        }

        private String extractContent(AgentResponse response) {
            return switch (response) {
                case TextResponse t -> t.content();
                case ToolRequest r -> "Tool: " + r.toolName();
                case PlanProposal p -> "Plan: " + p.steps().size() + " steps";
                case Error e -> "Error: " + e.message();
            };
        }
    }

    @Nested
    class TimestampTest {

        @Test
        void shouldHaveTimestampOnAllTypes() {
            Instant before = Instant.now();

            AgentResponse text = TextResponse.of("text");
            AgentResponse tool = ToolRequest.of("tool", Map.of());
            AgentResponse plan = PlanProposal.of(List.of(), "");
            AgentResponse error = Error.of("error");

            Instant after = Instant.now();

            assertThat(text.timestamp()).isBetween(before, after.plusMillis(1));
            assertThat(tool.timestamp()).isBetween(before, after.plusMillis(1));
            assertThat(plan.timestamp()).isBetween(before, after.plusMillis(1));
            assertThat(error.timestamp()).isBetween(before, after.plusMillis(1));
        }
    }
}
