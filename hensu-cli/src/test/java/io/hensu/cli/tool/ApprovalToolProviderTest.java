package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.review.ApprovalOutcome;
import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.cli.review.ToolApprovalRequest;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.ToolProvider;
import io.hensu.core.tool.UncontainedOnApproval;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApprovalToolProviderTest {

    private static final ToolDefinition DEPLOY =
            ToolDefinition.of("deploy", "Deploy the service", List.of());

    @Nested
    class WhenPolicyIsSatisfied {

        @Test
        void shouldDelegateTheCallUnchanged() {
            RecordingProvider delegate = new RecordingProvider(preview(true, false));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.REJECTED);

            ToolCallResult result =
                    provider.call("deploy", Map.of("environment", "prod"), unattendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(delegate.calls).containsExactly("deploy");
        }

        @Test
        void shouldAttachTheArgvAReviewerWouldHaveSeenSoTheAuditRecordsIt() {
            RecordingProvider delegate = new RecordingProvider(preview(true, false));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), unattendedRun());

            // The loop never sees an argv and the provider does not return one; this is
            // the only layer holding the resolved invocation the trail has to record.
            assertThat(result.argv()).containsExactly("/usr/bin/deploy", "prod");
        }
    }

    @Nested
    class WhenTheRunHasNobodyToAsk {

        @Test
        void shouldRefuseWithoutTouchingTheDelegate() {
            RecordingProvider delegate = new RecordingProvider(preview(false, false));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), unattendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(delegate.calls).isEmpty();
        }

        @Test
        void shouldSayWhichGateRefusedRatherThanOneSentenceForEveryRefusal() {
            ApprovalToolProvider unattendedFalse =
                    gated(new RecordingProvider(preview(false, false)), ApprovalOutcome.APPROVED);
            ApprovalToolProvider approvalRequired =
                    gated(new RecordingProvider(preview(true, true)), ApprovalOutcome.APPROVED);

            String first = unattendedFalse.call("deploy", Map.of(), unattendedRun()).error();
            String second = approvalRequired.call("deploy", Map.of(), unattendedRun()).error();

            // An agent reading "not granted to this node" looks for another tool; one
            // reading "this run is unattended" stops and reports. Same string for both
            // would collapse that distinction.
            assertThat(first).contains("unattended: false");
            assertThat(second).contains("approval: required");
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    class WhenAReviewerIsAsked {

        @Test
        void shouldRunWhatWasApproved() {
            RecordingProvider delegate = new RecordingProvider(preview(true, true));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), attendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(delegate.calls).containsExactly("deploy");
        }

        @Test
        void shouldShowTheResolvedArgvRatherThanTheTemplate() {
            RecordingProvider delegate = new RecordingProvider(preview(true, true));
            ScriptedHandler handler = new ScriptedHandler(ApprovalOutcome.REJECTED);
            ApprovalToolProvider provider =
                    new ApprovalToolProvider(delegate, new ToolApprovalGate(handler));

            provider.call("deploy", Map.of("environment", "prod"), attendedRun());

            assertThat(handler.seen)
                    .singleElement()
                    .satisfies(
                            request -> {
                                assertThat(request.argv())
                                        .containsExactly("/usr/bin/deploy", "prod");
                                assertThat(request.nodeId()).isEqualTo("publish");
                                assertThat(request.executionId()).isEqualTo("exec-1");
                            });
        }

        @Test
        void shouldRefuseWhatWasRejectedWithoutTouchingTheDelegate() {
            RecordingProvider delegate = new RecordingProvider(preview(true, true));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.REJECTED);

            ToolCallResult result = provider.call("deploy", Map.of(), attendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(delegate.calls).isEmpty();
        }

        @Test
        void shouldRefuseWhenTheReviewChannelDisappearedMidRun() {
            RecordingProvider delegate = new RecordingProvider(preview(true, true));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.NO_REVIEWER);

            ToolCallResult result = provider.call("deploy", Map.of(), attendedRun());

            // A question that could not be asked is not an approval.
            assertThat(result.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(delegate.calls).isEmpty();
        }
    }

    @Nested
    class WhenContainmentIsUnavailable {

        @Test
        void shouldRunUncontainedOnlyAfterAReviewerApprovedThatExactCall() {
            SandboxlessProvider delegate = new SandboxlessProvider();
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), attendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(delegate.uncontainedCalls).containsExactly("deploy");
            assertThat(delegate.containedCalls).isEmpty();
        }

        @Test
        void shouldNeverReachTheUncontainedPathInARunWithNoReviewer() {
            SandboxlessProvider delegate = new SandboxlessProvider();
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), unattendedRun());

            assertThat(result.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(delegate.uncontainedCalls).isEmpty();
            assertThat(delegate.containedCalls).isEmpty();
        }
    }

    @Nested
    class WhenTheDelegateMisbehaves {

        @Test
        void shouldTreatAProviderThatCannotDescribeACallAsUnsafeUnattended() {
            ThrowingPreviewProvider delegate = new ThrowingPreviewProvider();
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.APPROVED);

            ToolCallResult result = provider.call("deploy", Map.of(), unattendedRun());

            // A throwing preview must not abort the node — finding 3 — and must not be
            // read as "nothing objected, go ahead".
            assertThat(result.status()).isEqualTo(ToolCallStatus.DENIED);
            assertThat(delegate.calls).isEmpty();
        }
    }

    @Nested
    class TheCatalogItPublishes {

        @Test
        void shouldOfferEvenTheEntriesThisRunWillRefuse() {
            RecordingProvider delegate = new RecordingProvider(preview(false, false));
            ApprovalToolProvider provider = gated(delegate, ApprovalOutcome.REJECTED);

            // Hiding them would turn a routable capability gap naming the entry an
            // operator must grant into an agent reporting that the job is impossible.
            assertThat(provider.tools()).extracting(ToolDefinition::name).containsExactly("deploy");
            assertThat(provider.provides("deploy")).isTrue();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static ApprovalToolProvider gated(ToolProvider delegate, ApprovalOutcome outcome) {
        return new ApprovalToolProvider(
                delegate, new ToolApprovalGate(new ScriptedHandler(outcome)));
    }

    private static ToolPreview preview(boolean unattendedSafe, boolean approvalRequired) {
        return new ToolPreview(
                "deploy prod",
                List.of("/usr/bin/deploy", "prod"),
                "network: on, writes: nothing",
                unattendedSafe,
                approvalRequired);
    }

    private static Map<String, Object> unattendedRun() {
        Map<String, Object> context = new HashMap<>();
        context.put(ToolApprovalGate.RUN_MODE_KEY, true);
        context.put("_execution_id", "exec-1");
        context.put("current_node", "publish");
        return context;
    }

    private static Map<String, Object> attendedRun() {
        Map<String, Object> context = unattendedRun();
        context.put(ToolApprovalGate.RUN_MODE_KEY, false);
        return context;
    }

    /// Review handler answering with one scripted outcome and recording what it was asked.
    private static final class ScriptedHandler extends DaemonReviewHandler {

        private final ApprovalOutcome outcome;
        private final List<ToolApprovalRequest> seen = new ArrayList<>();

        ScriptedHandler(ApprovalOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public ApprovalOutcome requestToolApproval(ToolApprovalRequest request) {
            seen.add(request);
            return outcome;
        }
    }

    private static class RecordingProvider implements ToolProvider, PreviewCapable {

        private final ToolPreview preview;
        final List<String> calls = new ArrayList<>();

        RecordingProvider(ToolPreview preview) {
            this.preview = preview;
        }

        @Override
        public List<ToolDefinition> tools() {
            return List.of(DEPLOY);
        }

        @Override
        public boolean provides(String toolName) {
            return DEPLOY.name().equals(toolName);
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            calls.add(toolName);
            return ToolCallResult.success(toolName, "deployed");
        }

        @Override
        public ToolPreview preview(String toolName, Map<String, Object> arguments) {
            return preview;
        }
    }

    /// Provider whose preview reports that containment is gone, and which can waive it.
    private static final class SandboxlessProvider
            implements ToolProvider, PreviewCapable, UncontainedOnApproval {

        final List<String> containedCalls = new ArrayList<>();
        final List<String> uncontainedCalls = new ArrayList<>();

        @Override
        public List<ToolDefinition> tools() {
            return List.of(DEPLOY);
        }

        @Override
        public boolean provides(String toolName) {
            return DEPLOY.name().equals(toolName);
        }

        @Override
        public ToolCallResult call(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            containedCalls.add(toolName);
            return ToolCallResult.of(
                    toolName, ToolCallStatus.SANDBOX_UNAVAILABLE, null, "no backend", null);
        }

        @Override
        public ToolCallResult callUncontained(
                String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            uncontainedCalls.add(toolName);
            return ToolCallResult.success(toolName, "deployed uncontained");
        }

        @Override
        public ToolPreview preview(String toolName, Map<String, Object> arguments) {
            return new ToolPreview(
                    "deploy: cannot be prepared – no sandbox backend",
                    List.of(),
                    "network: on",
                    true,
                    false,
                    ToolCallStatus.SANDBOX_UNAVAILABLE);
        }
    }

    private static final class ThrowingPreviewProvider extends RecordingProvider {

        ThrowingPreviewProvider() {
            super(null);
        }

        @Override
        public ToolPreview preview(String toolName, Map<String, Object> arguments) {
            throw new IllegalStateException("template is broken");
        }
    }
}
