package io.hensu.core.execution.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.execution.result.ResultStatus;
import io.hensu.core.state.HensuState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusEvaluatorTest {

    private ConsensusEvaluator evaluator;
    private final ExecutionListener listener = ExecutionListener.NOOP;

    @BeforeEach
    void setUp() {
        evaluator = new ConsensusEvaluator();
    }

    // -- Helpers --

    private static BranchResult branchWithYields(
            String branchId, String output, Map<String, Object> yields) {
        return new BranchResult(
                branchId, new NodeResult(ResultStatus.SUCCESS, output, Map.of()), yields, 1.0);
    }

    private static BranchResult branchWithYields(
            String branchId, String output, Map<String, Object> yields, double weight) {
        return new BranchResult(
                branchId, new NodeResult(ResultStatus.SUCCESS, output, Map.of()), yields, weight);
    }

    private static BranchResult approving(String branchId, double score) {
        return branchWithYields(
                branchId,
                "output",
                Map.of(EngineVariables.SCORE, score, EngineVariables.APPROVED, true));
    }

    private static BranchResult rejecting(String branchId, double score) {
        return branchWithYields(
                branchId,
                "output",
                Map.of(EngineVariables.SCORE, score, EngineVariables.APPROVED, false));
    }

    @Nested
    class VoteExtractionTest {

        @Test
        void shouldReadScoreAndApprovalFromYields() {
            BranchResult br =
                    branchWithYields(
                            "b1",
                            "output",
                            Map.of(EngineVariables.SCORE, 85.0, EngineVariables.APPROVED, true));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.APPROVE);
            assertThat(votes.get("b1").score()).isEqualTo(85.0);
        }

        @Test
        void shouldHandleStringlyTypedScoreFromLlm() {
            // LLMs frequently return numbers as strings: {"score": "85"} instead of {"score": 85}
            // Prod code uses `instanceof Number` which silently defaults to 0.0 for strings.
            BranchResult br =
                    branchWithYields(
                            "b1",
                            "output",
                            Map.of(EngineVariables.SCORE, "85", EngineVariables.APPROVED, true));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Current behavior: string score defaults to 0.0 (not parsed as number)
            assertThat(votes.get("b1").score()).isEqualTo(0.0);
            // Vote type still comes from explicit approved=true
            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.APPROVE);
        }

        @Test
        void shouldDeriveApprovalFromScoreWhenApprovedFieldMissing() {
            // score=85 >= default threshold 70 -> APPROVE
            BranchResult br = branchWithYields("b1", "output", Map.of(EngineVariables.SCORE, 85.0));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.APPROVE);
        }

        @Test
        void shouldDefaultToZeroScoreWhenYieldsEmpty() {
            BranchResult br = branchWithYields("b1", "output", Map.of());
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            assertThat(votes.get("b1").score()).isEqualTo(0.0);
            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.REJECT);
        }
    }

    @Nested
    class MajorityVoteTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldNotReachMajorityOnExactTieAtFiftyPercent() throws Exception {
            // 2/4 approve at 50% threshold -> need >2.0 -> 2 > 2 is false (strict)
            List<BranchResult> results =
                    List.of(
                            approving("b1", 85.0),
                            approving("b2", 80.0),
                            rejecting("b3", 30.0),
                            rejecting("b4", 25.0));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, 0.5);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldNotReachMajorityWhenCustomThresholdNotMet() throws Exception {
            // 2/3 approve, threshold=0.8 -> need >2.4 -> 2 > 2 is false
            List<BranchResult> results =
                    List.of(approving("b1", 85.0), approving("b2", 80.0), rejecting("b3", 30.0));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, 0.8);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
            assertThat(result.reasoning()).contains("threshold=80%");
        }
    }

    @Nested
    class UnanimousTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldReachUnanimousWhenAllApprove() throws Exception {
            List<BranchResult> results =
                    List.of(approving("b1", 95.0), approving("b2", 80.0), approving("b3", 72.0));
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldNotReachUnanimousOnEmptyVotes() throws Exception {
            // Stream.allMatch() returns true on empty streams -- our code must guard against this
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            ConsensusResult result =
                    evaluator.evaluate(config, List.of(), emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
            assertThat(result.reasoning()).contains("no branches");
        }
    }

    @Nested
    class WeightedVoteTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldReachConsensusWhenApproveWeightDominates() throws Exception {
            // b1: approve (70*0.5=35), b2: approve (80*0.5=40), b3: reject (99*0.5=49.5)
            // approveRatio = 75/(75+49.5) = 0.60 > 0.5 -> consensus reached
            List<BranchResult> results =
                    List.of(
                            branchWithYields(
                                    "b1",
                                    "output",
                                    Map.of(
                                            EngineVariables.SCORE,
                                            70.0,
                                            EngineVariables.APPROVED,
                                            true),
                                    0.5),
                            branchWithYields(
                                    "b2",
                                    "best output",
                                    Map.of(
                                            EngineVariables.SCORE,
                                            80.0,
                                            EngineVariables.APPROVED,
                                            true),
                                    0.5),
                            branchWithYields(
                                    "b3",
                                    "reject output",
                                    Map.of(
                                            EngineVariables.SCORE,
                                            99.0,
                                            EngineVariables.APPROVED,
                                            false),
                                    0.5));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldHandleZeroTotalWeight() throws Exception {
            // All weights are 0.0 -> totalWeighted = 0 -> approveRatio = 0 -> no consensus
            // Guards against NaN from 0/0 division
            List<BranchResult> results =
                    List.of(
                            branchWithYields(
                                    "b1",
                                    "output",
                                    Map.of(
                                            EngineVariables.SCORE,
                                            90.0,
                                            EngineVariables.APPROVED,
                                            true),
                                    0.0),
                            branchWithYields(
                                    "b2",
                                    "output",
                                    Map.of(
                                            EngineVariables.SCORE,
                                            80.0,
                                            EngineVariables.APPROVED,
                                            true),
                                    0.0));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
            assertThat(result.reasoning()).doesNotContain("NaN");
        }
    }

    @Nested
    class JudgeDecidesTest {

        @Mock private AgentRegistry agentRegistry;
        @Mock private Agent judgeAgent;

        @Test
        void shouldApproveWhenJudgeApproves() throws Exception {
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            String judgeJson =
                    """
                    {"decision": true, "winning_branch": "b1", \
                    "reasoning": "Good quality"}""";
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of(judgeJson));

            List<BranchResult> results =
                    List.of(
                            branchWithYields("b1", "proposal A", Map.of("proposal", "A")),
                            branchWithYields("b2", "proposal B", Map.of("proposal", "B")));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isTrue();
            assertThat(result.winningBranchId()).isEqualTo("b1");
            assertThat(result.finalOutput()).isEqualTo(judgeJson);
            assertThat(result.reasoning()).isEqualTo("Good quality");
        }

        @Test
        void shouldRejectWhenJudgeRejects() throws Exception {
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            String judgeJson =
                    """
                    {"decision": false, "winning_branch": null, \
                    "reasoning": "Poor quality"}""";
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of(judgeJson));

            List<BranchResult> results =
                    List.of(
                            branchWithYields("b1", "proposal A", Map.of("proposal", "A")),
                            branchWithYields("b2", "proposal B", Map.of("proposal", "B")));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
            assertThat(result.finalOutput()).isEqualTo(judgeJson);
            assertThat(result.reasoning()).isEqualTo("Poor quality");
        }

        @Test
        void shouldRejectUnknownWinningBranch() throws Exception {
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(
                            AgentResponse.TextResponse.of(
                                    """
                            {"decision": true, "winning_branch": "ghost_branch", \
                            "reasoning": "Best analysis"}"""));

            List<BranchResult> results =
                    List.of(branchWithYields("b1", "proposal A", Map.of("proposal", "A")));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            // Ghost branch ID must be rejected -- winningBranchId should be null
            assertThat(result.consensusReached()).isTrue();
            assertThat(result.winningBranchId()).isNull();
        }

        @Test
        void shouldUseRawJudgeResponseAsFinalOutput() throws Exception {
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            String rawResponse =
                    """
                    {"decision": true, "winning_branch": "b1", \
                    "reasoning": "OK"}""";
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(AgentResponse.TextResponse.of(rawResponse));

            List<BranchResult> results =
                    List.of(branchWithYields("b1", "proposal A", Map.of("proposal", "A")));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            // finalOutput is always the raw judge response (no final_output extraction)
            assertThat(result.finalOutput()).isEqualTo(rawResponse);
        }

        @Test
        void shouldDegradeGracefullyOnInvalidJudgeJson() throws Exception {
            // Judge returns prose instead of JSON -> extractOutputParams extracts nothing
            // -> decision is not Boolean -> consensusReached=false, fallback reasoning
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(
                            AgentResponse.TextResponse.of(
                                    "I think branch b1 is better but I'm not sure."));

            List<BranchResult> results =
                    List.of(
                            branchWithYields("b1", "proposal A", Map.of("proposal", "A")),
                            branchWithYields("b2", "proposal B", Map.of("proposal", "B")));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry, listener);

            assertThat(result.consensusReached()).isFalse();
            assertThat(result.reasoning()).contains("rejected");
        }

        @Test
        void shouldThrowWhenJudgeAgentMissing() {
            List<BranchResult> results =
                    List.of(branchWithYields("b1", "proposal A", Map.of("proposal", "A")));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.JUDGE_DECIDES, null);

            assertThatThrownBy(
                            () ->
                                    evaluator.evaluate(
                                            config, results, emptyState(), agentRegistry, listener))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("judge agent ID");
        }
    }

    private static HensuState emptyState() {
        return new HensuState(
                new HashMap<>(),
                "test-workflow",
                "test-node",
                new io.hensu.core.execution.result.ExecutionHistory());
    }
}
