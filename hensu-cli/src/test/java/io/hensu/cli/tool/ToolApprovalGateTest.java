package io.hensu.cli.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.review.DaemonReviewHandler;
import io.hensu.cli.tool.ToolApprovalGate.Verdict;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolPreview;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// One test per row of the unattended × approval matrix, plus the sandbox rows.
///
/// The matrix is the single point where a run's mode meets an entry's declarations, and
/// getting one cell wrong is either a process launched with nobody watching or a
/// deployment whose tools all refuse. Both are silent until they matter.
class ToolApprovalGateTest {

    private static final boolean ATTENDED = false;
    private static final boolean UNATTENDED = true;

    @Nested
    class TheMatrix {

        @Test
        void shouldRunAnOrdinaryEntryInAnAttendedRun() {
            assertThat(ToolApprovalGate.decide(ATTENDED, entry(true, false)))
                    .isEqualTo(Verdict.RUN);
        }

        @Test
        void shouldAskAboutAnApprovalRequiredEntryInAnAttendedRun() {
            assertThat(ToolApprovalGate.decide(ATTENDED, entry(true, true))).isEqualTo(Verdict.ASK);
        }

        @Test
        void shouldRunAnUnattendedSafeEntryWithNobodyWatching() {
            assertThat(ToolApprovalGate.decide(UNATTENDED, entry(true, false)))
                    .isEqualTo(Verdict.RUN);
        }

        @Test
        void shouldRefuseAnEntryDeclaredUnattendedFalseRatherThanAskingNobody() {
            assertThat(ToolApprovalGate.decide(UNATTENDED, entry(false, false)))
                    .isEqualTo(Verdict.REFUSE);
        }

        @Test
        void shouldRefuseAnApprovalRequiredEntryWhateverItsUnattendedFlagSays() {
            // `approval: required` outranks `unattended: true`: the declaration asks for a
            // human, and escalating to one who is not there would just block the run.
            assertThat(ToolApprovalGate.decide(UNATTENDED, entry(true, true)))
                    .isEqualTo(Verdict.REFUSE);
        }
    }

    @Nested
    class WhenContainmentIsGone {

        @Test
        void shouldDemoteToApprovalRatherThanRunningUncontained() {
            assertThat(ToolApprovalGate.decide(ATTENDED, blocked())).isEqualTo(Verdict.ASK);
        }

        @Test
        void shouldRefuseWhenTheDemotionHasNowhereToGo() {
            assertThat(ToolApprovalGate.decide(UNATTENDED, blocked())).isEqualTo(Verdict.REFUSE);
        }

        @Test
        void shouldOutrankAnEntryThatWouldOtherwiseHaveRunUnattended() {
            ToolPreview sandboxless =
                    new ToolPreview(
                            "run-tests",
                            List.of("/usr/bin/true"),
                            "network: off",
                            true,
                            false,
                            ToolCallStatus.SANDBOX_UNAVAILABLE);

            assertThat(ToolApprovalGate.decide(UNATTENDED, sandboxless)).isEqualTo(Verdict.REFUSE);
        }
    }

    @Nested
    class WhenNothingCanDescribeTheCall {

        @Test
        void shouldRefuseUnattendedBecauseNothingCanSayWhatItWouldDo() {
            assertThat(ToolApprovalGate.decide(UNATTENDED, null)).isEqualTo(Verdict.REFUSE);
        }

        @Test
        void shouldStillRunAttendedBecauseNoDeclarationAsksForAHuman() {
            // Asking a reviewer to approve a call nobody can describe is asking for a
            // rubber stamp, which is worse than not asking.
            assertThat(ToolApprovalGate.decide(ATTENDED, null)).isEqualTo(Verdict.RUN);
        }
    }

    @Nested
    class WhereTheRunModeComesFrom {

        private final ToolApprovalGate gate = new ToolApprovalGate(new DaemonReviewHandler());

        @Test
        void shouldReadTheModeTheCliWroteIntoTheContext() {
            Map<String, Object> attended = new HashMap<>();
            attended.put(ToolApprovalGate.RUN_MODE_KEY, false);

            assertThat(gate.unattended(attended)).isFalse();
        }

        @Test
        void shouldTreatAContextWithNoModeAsUnattended() {
            // A deployment that never installed a reviewer has none. Defaulting the other
            // way would make "nobody wired this up" the condition under which the most
            // dangerous calls run.
            assertThat(gate.unattended(new HashMap<>())).isTrue();
            assertThat(gate.unattended(null)).isTrue();
        }
    }

    @Nested
    class WhoAnswersForTheWholeProcess {

        private final ToolApprovalGate gate = new ToolApprovalGate(new DaemonReviewHandler());

        @Test
        void shouldNameTheSoleAttendedRun() {
            gate.setRunMode("exec-1", ATTENDED);

            assertThat(gate.attendedReviewer()).contains("exec-1");
        }

        @Test
        void shouldRefuseWhenTheSoleRunIsUnattended() {
            gate.setRunMode("exec-1", UNATTENDED);

            assertThat(gate.attendedReviewer()).isEmpty();
        }

        @Test
        void shouldRefuseWhenAnUnattendedRunSharesTheDaemonWithAnAttendedOne() {
            gate.setRunMode("attended", ATTENDED);
            gate.setRunMode("unattended", UNATTENDED);

            // The decision this answers starts a server that outlives every run in the
            // daemon. Reading a single last-writer-wins field let the attended run's
            // reviewer consent on behalf of a run that never had one.
            assertThat(gate.attendedReviewer()).isEmpty();
        }

        @Test
        void shouldRefuseWhenTwoAttendedRunsBothCouldAnswer() {
            gate.setRunMode("exec-1", ATTENDED);
            gate.setRunMode("exec-2", ATTENDED);

            // Nothing here can say which human owns a process-wide decision, and picking
            // one silently binds the other.
            assertThat(gate.attendedReviewer()).isEmpty();
        }

        @Test
        void shouldForgetARunOnceItEnds() {
            gate.setRunMode("finished", UNATTENDED);
            gate.setRunMode("live", ATTENDED);
            gate.endRun("finished");

            // Without removal a daemon's first unattended run would veto every launch for
            // the rest of the process's life.
            assertThat(gate.attendedReviewer()).contains("live");
        }

        @Test
        void shouldRefuseWhenNoRunHasClaimedTheProcess() {
            assertThat(gate.attendedReviewer()).isEmpty();
        }
    }

    private static ToolPreview entry(boolean unattendedSafe, boolean approvalRequired) {
        return new ToolPreview(
                "run-tests",
                List.of("/usr/bin/true"),
                "network: off",
                unattendedSafe,
                approvalRequired);
    }

    private static ToolPreview blocked() {
        return new ToolPreview(
                "run-tests: cannot be prepared",
                List.of(),
                "network: off",
                true,
                false,
                ToolCallStatus.SANDBOX_UNAVAILABLE);
    }
}
