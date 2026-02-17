package io.hensu.core.execution.parallel;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.executor.NodeResult;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.JsonUtil;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Evaluates consensus from parallel branch execution results.
///
/// Supports multiple consensus strategies:
/// - **MAJORITY_VOTE**: Consensus when more than half approve
/// - **UNANIMOUS**: Consensus only when all branches approve
/// - **WEIGHTED_VOTE**: Weighted scoring with configurable threshold
/// - **JUDGE_DECIDES**: External agent makes the final decision
///
/// ### Vote Extraction
/// Votes are extracted from branch outputs by:
/// 1. Checking metadata for explicit "score" field
/// 2. Parsing output text for score/rating patterns
/// 3. Looking for approval/rejection keywords
/// 4. Falling back to neutral score (50.0) if undetermined
///
/// @implNote **Not thread-safe**. Create a new instance for each evaluation
/// or synchronize externally.
///
/// @see ConsensusConfig for configuration options
/// @see ConsensusResult for evaluation output
public class ConsensusEvaluator {

    private static final Logger logger = Logger.getLogger(ConsensusEvaluator.class.getName());
    private static final double DEFAULT_THRESHOLD = 70.0;
    private static final double DEFAULT_MAJORITY_THRESHOLD = 0.5;
    private static final double DEFAULT_WEIGHTED_THRESHOLD = 0.5;
    private static final double NEUTRAL_SCORE = 50.0;
    private static final double REJECT_MARGIN = 20.0;

    /// Evaluates consensus from branch results using the configured strategy.
    ///
    /// @param config consensus configuration specifying strategy and parameters, not null
    /// @param branchResults list of results from parallel branch execution, not null
    /// @param state current workflow state for context, not null
    /// @param agentRegistry registry for looking up judge agent (JUDGE_DECIDES only), not null
    /// @return consensus evaluation result, never null
    /// @throws Exception if judge agent execution fails (JUDGE_DECIDES strategy)
    /// @throws IllegalStateException if JUDGE_DECIDES strategy lacks judge agent ID
    public ConsensusResult evaluate(
            ConsensusConfig config,
            List<BranchResult> branchResults,
            HensuState state,
            AgentRegistry agentRegistry)
            throws Exception {

        logger.info("Evaluating consensus with strategy: " + config.getStrategy());

        Map<String, ConsensusResult.Vote> votes = extractVotes(branchResults, config);

        return switch (config.getStrategy()) {
            case MAJORITY_VOTE -> evaluateMajorityVote(votes, config);
            case UNANIMOUS -> evaluateUnanimous(votes, config);
            case WEIGHTED_VOTE -> evaluateWeightedVote(votes, config);
            case JUDGE_DECIDES ->
                    evaluateJudgeDecides(votes, branchResults, config, state, agentRegistry);
        };
    }

    /// Extracts votes from branch results using rubric evaluation or text heuristics.
    ///
    /// ### Vote Extraction Priority
    /// 1. **Rubric** (authoritative): If metadata contains `rubric_passed`, use rubric score
    ///    and pass/fail status directly — `passed` maps to APPROVE, `failed` maps to REJECT
    /// 2. **Metadata score**: Check for explicit `score` field in metadata
    /// 3. **Text patterns**: Parse output for "score" or "rating" followed by a number
    /// 4. **Keywords**: Detect approve/reject/abstain keywords in output text
    /// 5. **Threshold fallback**: Compare extracted score against configured threshold
    ///
    /// @param branchResults list of branch execution results, not null
    /// @param config consensus configuration for threshold, not null
    /// @return map of branch ID to extracted vote, never null
    public Map<String, ConsensusResult.Vote> extractVotes(
            List<BranchResult> branchResults, ConsensusConfig config) {

        Map<String, ConsensusResult.Vote> votes = new HashMap<>();
        double defaultWeight = 1.0 / branchResults.size();

        for (BranchResult br : branchResults) {
            String branchId = br.getBranchId();
            NodeResult result = br.getResult();
            String output = result.getOutput() != null ? result.getOutput().toString() : "";
            Map<String, Object> metadata = result.getMetadata();

            double score;
            ConsensusResult.VoteType voteType;

            if (metadata.containsKey("rubric_passed")) {
                // Rubric evaluation is authoritative — overrides text heuristics
                boolean rubricPassed = (Boolean) metadata.get("rubric_passed");
                score =
                        metadata.containsKey("rubric_score")
                                ? ((Number) metadata.get("rubric_score")).doubleValue()
                                : NEUTRAL_SCORE;
                voteType =
                        rubricPassed
                                ? ConsensusResult.VoteType.APPROVE
                                : ConsensusResult.VoteType.REJECT;
            } else {
                // Fall through to text-based heuristics
                score = extractScore(output, metadata);
                voteType = determineVoteType(output, score, config.getThreshold());
            }

            double weight =
                    metadata.containsKey("weight")
                            ? ((Number) metadata.get("weight")).doubleValue()
                            : defaultWeight;

            votes.put(
                    branchId,
                    new ConsensusResult.Vote(branchId, branchId, voteType, score, weight, output));
        }

        return votes;
    }

    /// Extracts a numerical score from output text or metadata.
    ///
    /// @param output the branch output text, not null
    /// @param metadata the result metadata map, not null
    /// @return extracted score, or NEUTRAL_SCORE if not found
    private double extractScore(String output, Map<String, Object> metadata) {
        if (metadata.containsKey("score")) {
            Object scoreObj = metadata.get("score");
            if (scoreObj instanceof Number) {
                return ((Number) scoreObj).doubleValue();
            }
        }

        Pattern scorePattern =
                Pattern.compile(
                        "(?:score|rating)[\":\\s]*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = scorePattern.matcher(output);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }

        return NEUTRAL_SCORE;
    }

    /// Determines vote type from output keywords or score threshold.
    ///
    /// @param output the branch output text, not null
    /// @param score the extracted score value
    /// @param threshold configured threshold, may be null (uses default)
    /// @return the determined vote type, never null
    private ConsensusResult.VoteType determineVoteType(
            String output, double score, Double threshold) {
        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("approve")
                || lowerOutput.contains("accept")
                || lowerOutput.contains("pass")) {
            return ConsensusResult.VoteType.APPROVE;
        }
        if (lowerOutput.contains("reject")
                || lowerOutput.contains("deny")
                || lowerOutput.contains("fail")) {
            return ConsensusResult.VoteType.REJECT;
        }
        if (lowerOutput.contains("abstain") || lowerOutput.contains("neutral")) {
            return ConsensusResult.VoteType.ABSTAIN;
        }

        double effectiveThreshold = threshold != null ? threshold : DEFAULT_THRESHOLD;
        if (score >= effectiveThreshold) {
            return ConsensusResult.VoteType.APPROVE;
        } else if (score < effectiveThreshold - REJECT_MARGIN) {
            return ConsensusResult.VoteType.REJECT;
        } else {
            return ConsensusResult.VoteType.ABSTAIN;
        }
    }

    /// Evaluates consensus using majority vote strategy.
    ///
    /// Consensus is reached when the number of approvals meets or exceeds
    /// the threshold percentage of total votes. Defaults to 50% when no
    /// threshold is configured.
    ///
    /// @param votes map of branch votes, not null
    /// @param config consensus configuration with optional threshold, not null
    /// @return consensus result, never null
    private ConsensusResult evaluateMajorityVote(
            Map<String, ConsensusResult.Vote> votes, ConsensusConfig config) {

        long approveCount = votes.values().stream().filter(ConsensusResult.Vote::isApprove).count();
        long rejectCount = votes.values().stream().filter(ConsensusResult.Vote::isReject).count();
        long total = votes.size();

        double effectiveThreshold =
                config.getThreshold() != null ? config.getThreshold() : DEFAULT_MAJORITY_THRESHOLD;
        long requiredApprovals = (long) Math.ceil(total * effectiveThreshold);
        boolean consensusReached = approveCount >= requiredApprovals;

        ConsensusResult.Vote winningVote =
                votes.values().stream()
                        .filter(
                                v ->
                                        v.voteType()
                                                == (consensusReached
                                                        ? ConsensusResult.VoteType.APPROVE
                                                        : ConsensusResult.VoteType.REJECT))
                        .max(Comparator.comparingDouble(ConsensusResult.Vote::score))
                        .orElse(null);

        String reasoning =
                String.format(
                        "Majority vote: %d approve, %d reject out of %d total"
                                + " (threshold=%.0f%%, required=%d). %s",
                        approveCount,
                        rejectCount,
                        total,
                        effectiveThreshold * 100,
                        requiredApprovals,
                        consensusReached ? "Consensus reached." : "Consensus not reached.");

        return ConsensusResult.builder()
                .consensusReached(consensusReached)
                .strategyUsed(config.getStrategy())
                .winningBranchId(winningVote != null ? winningVote.branchId() : null)
                .finalOutput(winningVote != null ? winningVote.output() : "No consensus")
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus using unanimous vote strategy.
    ///
    /// @param votes map of branch votes, not null
    /// @param config consensus configuration, not null
    /// @return consensus result, never null
    private ConsensusResult evaluateUnanimous(
            Map<String, ConsensusResult.Vote> votes, ConsensusConfig config) {

        boolean allApprove = votes.values().stream().allMatch(ConsensusResult.Vote::isApprove);
        boolean anyReject = votes.values().stream().anyMatch(ConsensusResult.Vote::isReject);

        ConsensusResult.Vote highestVote =
                votes.values().stream()
                        .max(Comparator.comparingDouble(ConsensusResult.Vote::score))
                        .orElse(null);

        String reasoning;
        if (allApprove) {
            reasoning = "Unanimous approval: all " + votes.size() + " branches approved.";
        } else if (anyReject) {
            long rejectCount =
                    votes.values().stream().filter(ConsensusResult.Vote::isReject).count();
            reasoning =
                    String.format("Unanimous not reached: %d branch(es) rejected.", rejectCount);
        } else {
            reasoning = "Unanimous not reached: some branches abstained.";
        }

        return ConsensusResult.builder()
                .consensusReached(allApprove)
                .strategyUsed(config.getStrategy())
                .winningBranchId(highestVote != null ? highestVote.branchId() : null)
                .finalOutput(
                        allApprove && highestVote != null
                                ? highestVote.output()
                                : "No unanimous consensus")
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus using weighted vote strategy.
    ///
    /// @param votes map of branch votes, not null
    /// @param config consensus configuration, not null
    /// @return consensus result, never null
    private ConsensusResult evaluateWeightedVote(
            Map<String, ConsensusResult.Vote> votes, ConsensusConfig config) {

        double weightedApprove =
                votes.values().stream()
                        .filter(ConsensusResult.Vote::isApprove)
                        .mapToDouble(v -> v.score() * v.weight())
                        .sum();

        double weightedReject =
                votes.values().stream()
                        .filter(ConsensusResult.Vote::isReject)
                        .mapToDouble(v -> v.score() * v.weight())
                        .sum();

        double threshold =
                config.getThreshold() != null ? config.getThreshold() : DEFAULT_WEIGHTED_THRESHOLD;
        double approveRatio =
                (weightedApprove + weightedReject) > 0
                        ? weightedApprove / (weightedApprove + weightedReject)
                        : 0;

        boolean consensusReached = approveRatio >= threshold;

        ConsensusResult.Vote winningVote =
                votes.values().stream()
                        .max(Comparator.comparingDouble(v -> v.score() * v.weight()))
                        .orElse(null);

        String reasoning =
                String.format(
                        "Weighted vote: approve=%.2f, reject=%.2f, ratio=%.2f (threshold=%.2f). %s",
                        weightedApprove,
                        weightedReject,
                        approveRatio,
                        threshold,
                        consensusReached ? "Consensus reached." : "Consensus not reached.");

        return ConsensusResult.builder()
                .consensusReached(consensusReached)
                .strategyUsed(config.getStrategy())
                .winningBranchId(winningVote != null ? winningVote.branchId() : null)
                .finalOutput(winningVote != null ? winningVote.output() : "No consensus")
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus by invoking a judge agent.
    ///
    /// @param votes map of branch votes, not null
    /// @param branchResults original branch results for judge context, not null
    /// @param config consensus configuration with judge agent ID, not null
    /// @param state workflow state for agent context, not null
    /// @param agentRegistry registry for looking up judge agent, not null
    /// @return consensus result based on judge decision, never null
    /// @throws IllegalStateException if judge agent ID is missing or agent not found
    private ConsensusResult evaluateJudgeDecides(
            Map<String, ConsensusResult.Vote> votes,
            List<BranchResult> branchResults,
            ConsensusConfig config,
            HensuState state,
            AgentRegistry agentRegistry) {

        String judgeAgentId = config.getJudgeAgentId();
        if (judgeAgentId == null || judgeAgentId.isBlank()) {
            throw new IllegalStateException("JUDGE_DECIDES strategy requires a judge agent ID");
        }

        Agent judgeAgent =
                agentRegistry
                        .getAgent(judgeAgentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Judge agent not found: " + judgeAgentId));

        String prompt = buildJudgePrompt(votes, branchResults);

        logger.info("Invoking judge agent: " + judgeAgentId);
        AgentResponse judgeResponse = judgeAgent.execute(prompt, state.getContext());

        String responseContent =
                switch (judgeResponse) {
                    case AgentResponse.TextResponse t -> t.content();
                    case AgentResponse.Error e ->
                            throw new IllegalStateException("Judge agent failed: " + e.message());
                    default ->
                            throw new IllegalStateException(
                                    "Unexpected response type from judge agent");
                };
        return parseJudgeResponse(responseContent, votes, config);
    }

    /// Builds the prompt for the judge agent.
    ///
    /// @param votes map of branch votes, not null
    /// @param branchResults original branch results, not null
    /// @return formatted judge prompt, never null
    private String buildJudgePrompt(
            Map<String, ConsensusResult.Vote> votes, List<BranchResult> branchResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "You are the judge for a consensus decision. Review the following branch results and make a final decision.\n\n");
        sb.append("## Branch Results:\n\n");

        for (BranchResult br : branchResults) {
            ConsensusResult.Vote vote = votes.get(br.getBranchId());
            sb.append(String.format("### Branch: %s\n", br.getBranchId()));
            sb.append(String.format("Vote: %s (Score: %.1f)\n", vote.voteType(), vote.score()));
            sb.append("Output:\n```\n");
            sb.append(br.getResult().getOutput());
            sb.append("\n```\n\n");
        }

        sb.append(
                """
                ## Your Task:
                1. Analyze all branch outputs
                2. Determine if consensus should be reached
                3. Select the best output or synthesize a final decision

                Respond with a JSON object containing:
                - "decision": "approve" or "reject"
                - "winning_branch": the branch ID with the best output (or null if synthesizing)
                - "reasoning": brief explanation of your decision
                - "final_output": the final output to use
                """);

        return sb.toString();
    }

    /// Parses the judge agent's response into a ConsensusResult.
    ///
    /// @param judgeOutput the judge's response text, not null
    /// @param votes original votes for inclusion in result, not null
    /// @param config consensus configuration, not null
    /// @return parsed consensus result, never null
    private ConsensusResult parseJudgeResponse(
            String judgeOutput, Map<String, ConsensusResult.Vote> votes, ConsensusConfig config) {

        boolean consensusReached =
                judgeOutput.toLowerCase().contains("\"decision\"")
                        ? judgeOutput.toLowerCase().contains("\"approve\"")
                        : !judgeOutput.toLowerCase().contains("reject");

        String winningBranch = JsonUtil.extractJsonField(judgeOutput, "winning_branch");
        String reasoning = JsonUtil.extractJsonField(judgeOutput, "reasoning");
        if (reasoning == null) {
            reasoning = "Judge decision: " + (consensusReached ? "approved" : "rejected");
        }

        return ConsensusResult.builder()
                .consensusReached(consensusReached)
                .strategyUsed(config.getStrategy())
                .winningBranchId(winningBranch)
                .finalOutput(judgeOutput)
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }
}
