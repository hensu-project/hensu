package io.hensu.core.execution.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
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

    @BeforeEach
    void setUp() {
        evaluator = new ConsensusEvaluator();
    }

    // -- Helpers --

    private static BranchResult branchWithOutput(String branchId, String output) {
        return new BranchResult(branchId, new NodeResult(ResultStatus.SUCCESS, output, Map.of()));
    }

    private static BranchResult branchWithMetadata(
            String branchId, String output, Map<String, Object> metadata) {
        return new BranchResult(branchId, new NodeResult(ResultStatus.SUCCESS, output, metadata));
    }

    private static BranchResult branchWithRubric(
            String branchId, String output, double rubricScore, boolean rubricPassed) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rubric_score", rubricScore);
        metadata.put("rubric_passed", rubricPassed);
        metadata.put("rubric_id", "test-rubric");
        return new BranchResult(branchId, new NodeResult(ResultStatus.SUCCESS, output, metadata));
    }

    @Nested
    class VoteExtractionTest {

        @Test
        void shouldPreferRubricScoreOverTextParsing() {
            // Given: rubric says APPROVE, text says "reject"
            BranchResult br = branchWithRubric("b1", "I reject this. Score: 20", 85.0, true);
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Then: rubric overrides text heuristics
            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.APPROVE);
            assertThat(votes.get("b1").score()).isEqualTo(85.0);
        }

        @Test
        void shouldFallbackToTextParsingWhenNoRubric() {
            // Given: no rubric metadata, output contains "approve" keyword
            BranchResult br = branchWithOutput("b1", "I approve this content");
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Then: keyword-based detection
            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.APPROVE);
        }

        @Test
        void shouldExtractScoreFromMetadata() {
            // Given: explicit score in metadata
            BranchResult br = branchWithMetadata("b1", "Some output", Map.of("score", 75.0));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Then
            assertThat(votes.get("b1").score()).isEqualTo(75.0);
        }

        @Test
        void shouldExtractScoreFromOutputPattern() {
            // Given: score embedded in output text
            BranchResult br = branchWithOutput("b1", "Analysis complete. Score: 82.5 out of 100");
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Then
            assertThat(votes.get("b1").score()).isEqualTo(82.5);
        }

        @Test
        void shouldDefaultToNeutralScoreWhenNothingFound() {
            // Given: no score in metadata, no patterns in output, no keywords
            BranchResult br = branchWithOutput("b1", "Some generic response");
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            Map<String, ConsensusResult.Vote> votes = evaluator.extractVotes(List.of(br), config);

            // Then: neutral score (50.0) → ABSTAIN (between threshold and threshold-20)
            assertThat(votes.get("b1").score()).isEqualTo(50.0);
            assertThat(votes.get("b1").voteType()).isEqualTo(ConsensusResult.VoteType.ABSTAIN);
        }
    }

    @Nested
    class MajorityVoteTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldReachMajorityWithDefaultThreshold() throws Exception {
            // Given: 2/3 approve, threshold=null (default 0.5 → need ceil(3*0.5)=2)
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve this"),
                            branchWithOutput("b2", "I approve this"),
                            branchWithOutput("b3", "I reject this"));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldNotReachMajorityWhenCustomThresholdNotMet() throws Exception {
            // Given: 2/3 approve, threshold=0.8 → need ceil(3*0.8)=3
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve this"),
                            branchWithOutput("b2", "I approve this"),
                            branchWithOutput("b3", "I reject this"));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, 0.8);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then: 2 approvals < 3 required
            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldReachMajorityWhenCustomThresholdMet() throws Exception {
            // Given: 3/3 approve, threshold=0.8 → need ceil(3*0.8)=3
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve"),
                            branchWithOutput("b2", "I accept"),
                            branchWithOutput("b3", "I pass this"));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, 0.8);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldUseRubricScoreForMajorityVote() throws Exception {
            // Given: 2 rubric-passed, 1 rubric-failed
            List<BranchResult> results =
                    List.of(
                            branchWithRubric("b1", "output", 90.0, true),
                            branchWithRubric("b2", "output", 85.0, true),
                            branchWithRubric("b3", "output", 40.0, false));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.MAJORITY_VOTE, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then: 2/3 approve → consensus
            assertThat(result.consensusReached()).isTrue();
            assertThat(result.approveCount()).isEqualTo(2);
            assertThat(result.rejectCount()).isEqualTo(1);
        }
    }

    @Nested
    class UnanimousTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldReachUnanimousWhenAllApprove() throws Exception {
            // Given: all 3 branches approve
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve"),
                            branchWithOutput("b2", "I accept this"),
                            branchWithOutput("b3", "I pass"));
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldNotReachUnanimousWhenAnyRejects() throws Exception {
            // Given: 2 approve, 1 rejects
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve"),
                            branchWithOutput("b2", "I approve"),
                            branchWithOutput("b3", "I reject this"));
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldNotReachUnanimousWhenAnyAbstains() throws Exception {
            // Given: 2 approve, 1 neutral (no keywords, neutral score → ABSTAIN)
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve"),
                            branchWithOutput("b2", "I approve"),
                            branchWithOutput("b3", "Hmm, not sure about this"));
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then: ABSTAIN ≠ APPROVE → not unanimous
            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldUseRubricScoreForUnanimous() throws Exception {
            // Given: all 3 branches pass rubric
            List<BranchResult> results =
                    List.of(
                            branchWithRubric("b1", "output", 95.0, true),
                            branchWithRubric("b2", "output", 80.0, true),
                            branchWithRubric("b3", "output", 72.0, true));
            ConsensusConfig config = new ConsensusConfig(null, ConsensusStrategy.UNANIMOUS, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then: all rubric-passed → unanimous
            assertThat(result.consensusReached()).isTrue();
        }
    }

    @Nested
    class WeightedVoteTest {

        @Mock private AgentRegistry agentRegistry;

        @Test
        void shouldReachWeightedConsensusAboveThreshold() throws Exception {
            // Given: high-score approver (90*0.6=54) vs low-score rejector (20*0.4=8)
            // approveRatio = 54/(54+8) = 0.87 > 0.5
            List<BranchResult> results =
                    List.of(
                            branchWithMetadata(
                                    "b1", "I approve", Map.of("score", 90.0, "weight", 0.6)),
                            branchWithMetadata(
                                    "b2", "I reject", Map.of("score", 20.0, "weight", 0.4)));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldNotReachWeightedConsensusBelowThreshold() throws Exception {
            // Given: low-score approver (30*0.3=9) vs high-score rejector (95*0.7=66.5)
            // approveRatio = 9/(9+66.5) = 0.119 < 0.5
            List<BranchResult> results =
                    List.of(
                            branchWithMetadata(
                                    "b1", "I approve", Map.of("score", 30.0, "weight", 0.3)),
                            branchWithMetadata(
                                    "b2", "I reject", Map.of("score", 95.0, "weight", 0.7)));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldIgnoreAbstainInWeightedCalculation() throws Exception {
            // Given: 1 approve (score=90), 1 abstain (neutral, no keywords)
            // Only approve contributes → ratio = 1.0
            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve. Score: 90"),
                            branchWithOutput("b2", "Some neutral comment"));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then: ABSTAIN excluded, only approve counted → ratio = 1.0 > 0.5
            assertThat(result.consensusReached()).isTrue();
        }

        @Test
        void shouldUseRubricScoreForWeightedVote() throws Exception {
            // Given: rubric-scored branches
            // b1: passed (score 85, weight 0.5) → weighted: 85*0.5 = 42.5
            // b2: failed (score 40, weight 0.5) → weighted: 40*0.5 = 20.0
            // approveRatio = 42.5/(42.5+20.0) = 0.68 > 0.5
            Map<String, Object> meta1 = new HashMap<>();
            meta1.put("rubric_score", 85.0);
            meta1.put("rubric_passed", true);
            meta1.put("weight", 0.5);
            Map<String, Object> meta2 = new HashMap<>();
            meta2.put("rubric_score", 40.0);
            meta2.put("rubric_passed", false);
            meta2.put("weight", 0.5);

            List<BranchResult> results =
                    List.of(
                            branchWithMetadata("b1", "output", meta1),
                            branchWithMetadata("b2", "output", meta2));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.WEIGHTED_VOTE, 0.5);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
        }
    }

    @Nested
    class JudgeDecidesTest {

        @Mock private AgentRegistry agentRegistry;
        @Mock private Agent judgeAgent;

        @Test
        void shouldApproveWhenJudgeApproves() throws Exception {
            // Given
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(
                            AgentResponse.TextResponse.of(
                                    """
                                    {"decision": "approve", "winning_branch": "b1", \
                                    "reasoning": "Good quality", "final_output": "Approved"}"""));

            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve. Score: 90"),
                            branchWithOutput("b2", "I reject. Score: 30"));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isTrue();
            assertThat(result.winningBranchId()).isEqualTo("b1");
        }

        @Test
        void shouldRejectWhenJudgeRejects() throws Exception {
            // Given
            when(agentRegistry.getAgent("judge")).thenReturn(Optional.of(judgeAgent));
            when(judgeAgent.execute(any(), any()))
                    .thenReturn(
                            AgentResponse.TextResponse.of(
                                    """
                                    {"decision": "reject", "winning_branch": null, \
                                    "reasoning": "Poor quality", "final_output": "Rejected"}"""));

            List<BranchResult> results =
                    List.of(
                            branchWithOutput("b1", "I approve. Score: 50"),
                            branchWithOutput("b2", "I reject. Score: 30"));
            ConsensusConfig config =
                    new ConsensusConfig("judge", ConsensusStrategy.JUDGE_DECIDES, null);

            // When
            ConsensusResult result =
                    evaluator.evaluate(config, results, emptyState(), agentRegistry);

            // Then
            assertThat(result.consensusReached()).isFalse();
        }

        @Test
        void shouldThrowWhenJudgeAgentMissing() {
            // Given
            List<BranchResult> results = List.of(branchWithOutput("b1", "output"));
            ConsensusConfig config =
                    new ConsensusConfig(null, ConsensusStrategy.JUDGE_DECIDES, null);

            // When/Then
            assertThatThrownBy(
                            () -> evaluator.evaluate(config, results, emptyState(), agentRegistry))
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
