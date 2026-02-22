package io.hensu.core.execution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hensu.core.execution.executor.ExecutionContext;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ExecutionHistory;
import io.hensu.core.execution.result.ExecutionResult;
import io.hensu.core.review.ReviewConfig;
import io.hensu.core.review.ReviewDecision;
import io.hensu.core.review.ReviewHandler;
import io.hensu.core.review.ReviewMode;
import io.hensu.core.state.HensuState;
import io.hensu.core.workflow.Workflow;
import io.hensu.core.workflow.node.Node;
import io.hensu.core.workflow.node.StandardNode;
import io.hensu.core.workflow.transition.SuccessTransition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ReviewProcessor")
@ExtendWith(MockitoExtension.class)
class ReviewPostProcessorTest {

    @Mock private ReviewHandler reviewHandler;

    private ReviewPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ReviewPostProcessor(reviewHandler);
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns empty when node has no review config")
        void shouldSkipWhenNoReviewConfig() {
            var ctx = contextWithReview("node", null);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when review mode is DISABLED")
        void shouldSkipWhenDisabled() {
            var config = new ReviewConfig(ReviewMode.DISABLED, false, false);
            var ctx = contextWithReview("node", config);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when OPTIONAL and result is SUCCESS — handler never called")
        void shouldSkipOptionalOnSuccess() {
            var config = new ReviewConfig(ReviewMode.OPTIONAL, false, false);
            var ctx = contextWithReview("node", config);

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
            // The handler must not be invoked — calling it would block automation unnecessarily.
            verify(reviewHandler, never()).requestReview(any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("review decisions")
    class ReviewDecisions {

        @Test
        @DisplayName("returns empty on Approve")
        void shouldContinueOnApprove() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, false, false);
            var ctx = contextWithReview("node", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Approve(null));

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty on Backtrack (state mutated)")
        void shouldReturnEmptyOnBacktrack() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, true, false);
            var ctx = contextWithReview("draft", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(
                            new ReviewDecision.Backtrack(
                                    "research", null, "Needs more work", null));

            var result = processor.process(ctx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("updates state currentNode on Backtrack")
        void shouldUpdateStateOnBacktrack() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, true, false);
            var ctx = contextWithReview("draft", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Backtrack("research", null, "Redo", null));

            processor.process(ctx);

            assertThat(ctx.state().getCurrentNode()).isEqualTo("research");
        }

        @Test
        @DisplayName("records backtrack event in history")
        void shouldRecordBacktrackInHistory() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, true, false);
            var ctx = contextWithReview("draft", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Backtrack("research", null, "Needs work", null));

            processor.process(ctx);

            assertThat(ctx.state().getHistory().getBacktracks()).hasSize(1);
        }

        @Test
        @DisplayName("returns Rejected on Reject")
        void shouldTerminateOnReject() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, false, false);
            var ctx = contextWithReview("node", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Reject("Unacceptable"));

            var result = processor.process(ctx);

            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(ExecutionResult.Rejected.class);
        }

        @Test
        @DisplayName("stores edited prompt on Backtrack with edited prompt")
        void shouldStoreEditedPrompt() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, true, true);
            var ctx = contextWithReview("draft", config);
            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(
                            new ReviewDecision.Backtrack(
                                    "research", null, "Try again", "Use a different approach"));

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .containsEntry("_prompt_override_research", "Use a different approach");
        }
    }

    @Nested
    @DisplayName("edited state handling")
    class EditedState {

        @Test
        @DisplayName("copies edited state fields on Approve with edits")
        void shouldCopyEditedStateOnApprove() {
            var config = new ReviewConfig(ReviewMode.REQUIRED, false, true);
            var ctx = contextWithReview("node", config);
            ctx.state().getContext().put("original", "value");

            var editedState =
                    new HensuState.Builder()
                            .executionId("test")
                            .workflowId("test-wf")
                            .currentNode("node")
                            .context(new HashMap<>(Map.of("edited", "new-value")))
                            .history(new ExecutionHistory())
                            .build();

            when(reviewHandler.requestReview(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ReviewDecision.Approve(editedState));

            processor.process(ctx);

            assertThat(ctx.state().getContext())
                    .containsEntry("edited", "new-value")
                    .doesNotContainKey("original");
        }
    }

    // --- Helpers ---

    private ProcessorContext contextWithReview(String nodeId, ReviewConfig reviewConfig) {
        Node node =
                StandardNode.builder()
                        .id(nodeId)
                        .reviewConfig(reviewConfig)
                        .transitionRules(List.of(new SuccessTransition("next")))
                        .build();

        var state =
                new HensuState.Builder()
                        .executionId("test")
                        .workflowId("test-wf")
                        .currentNode(nodeId)
                        .context(new HashMap<>())
                        .history(new ExecutionHistory())
                        .build();

        var workflow =
                Workflow.builder()
                        .id("test-wf")
                        .startNode(nodeId)
                        .nodes(Map.of(nodeId, node))
                        .build();

        var execCtx = ExecutionContext.builder().state(state).workflow(workflow).build();

        return new ProcessorContext(execCtx, node, NodeResult.success("output", Map.of()));
    }
}
