package io.hensu.core.execution.parallel;

import io.hensu.core.agent.Agent;
import io.hensu.core.agent.AgentRegistry;
import io.hensu.core.agent.AgentResponse;
import io.hensu.core.execution.EngineVariables;
import io.hensu.core.execution.ExecutionListener;
import io.hensu.core.state.HensuState;
import io.hensu.core.util.AgentOutputValidator;
import io.hensu.core.util.JsonUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/// Evaluates consensus from parallel branch execution results.
///
/// Reads structured data exclusively from {@link BranchResult#yields()} –
/// engine variables ({@code score}, {@code approved}) are extracted by the
/// processor pipeline, not parsed from raw text.
///
/// ### Supported strategies
/// - **MAJORITY_VOTE** – consensus when approvals strictly exceed threshold ratio
/// - **UNANIMOUS** – consensus only when every branch approves
/// - **WEIGHTED_VOTE** – weighted scoring with configurable threshold
/// - **JUDGE_DECIDES** – external agent makes the final decision
///
/// @implNote **Not thread-safe.** Create a new instance per evaluation
/// or synchronize externally.
///
/// @see ConsensusConfig for configuration options
/// @see ConsensusResult for evaluation output
public class ConsensusEvaluator {

    private static final Logger logger = Logger.getLogger(ConsensusEvaluator.class.getName());
    private static final double DEFAULT_THRESHOLD = 70.0;
    private static final double DEFAULT_MAJORITY_THRESHOLD = 0.5;
    private static final double DEFAULT_WEIGHTED_THRESHOLD = 0.5;

    // -- Judge protocol constants (internal to consensus, not engine variables) --
    private static final String JUDGE_DECISION = "decision";
    private static final String JUDGE_WINNING_BRANCH = "winning_branch";
    private static final String JUDGE_REASONING = "reasoning";

    /// All judge protocol fields for bulk extraction via
    /// {@link JsonUtil#extractOutputParams}.
    private static final List<String> JUDGE_FIELDS =
            List.of(JUDGE_DECISION, JUDGE_WINNING_BRANCH, JUDGE_REASONING);

    /// Evaluates consensus from branch results using the configured strategy.
    ///
    /// @param config consensus configuration specifying strategy and parameters, not null
    /// @param branchResults list of results from parallel branch execution, not null
    /// @param state current workflow state for context, not null
    /// @param agentRegistry registry for looking up judge agent (JUDGE_DECIDES only), not null
    /// @param listener execution listener for agent lifecycle events, not null
    /// @return consensus evaluation result, never null
    /// @throws Exception if judge agent execution fails (JUDGE_DECIDES strategy)
    /// @throws IllegalStateException if JUDGE_DECIDES strategy lacks judge agent ID
    public ConsensusResult evaluate(
            ConsensusConfig config,
            List<BranchResult> branchResults,
            HensuState state,
            AgentRegistry agentRegistry,
            ExecutionListener listener)
            throws Exception {

        logger.info("Evaluating consensus with strategy: " + config.getStrategy());

        // JUDGE_DECIDES skips vote extraction – branches don't self-score,
        // so extractVotes() would produce garbage defaults (score=0, approved=false).
        if (config.getStrategy() == ConsensusStrategy.JUDGE_DECIDES) {
            return evaluateJudgeDecides(branchResults, config, state, agentRegistry, listener);
        }

        Map<String, ConsensusResult.Vote> votes = extractVotes(branchResults, config);

        return switch (config.getStrategy()) {
            case MAJORITY_VOTE -> evaluateMajorityVote(votes, config);
            case UNANIMOUS -> evaluateUnanimous(votes, config);
            case WEIGHTED_VOTE -> evaluateWeightedVote(votes, config);
            case JUDGE_DECIDES -> throw new IllegalStateException("unreachable");
        };
    }

    /// Extracts votes from branch results using structured yields data.
    ///
    /// Reads {@code score} and {@code approved} directly from
    /// {@link BranchResult#yields()}. No text heuristics, no keyword
    /// detection, no regex parsing.
    ///
    /// @param branchResults list of branch execution results, not null
    /// @param config consensus configuration for threshold fallback, not null
    /// @return map of branch ID to extracted vote, never null (empty if input is empty)
    public Map<String, ConsensusResult.Vote> extractVotes(
            List<BranchResult> branchResults, ConsensusConfig config) {

        if (branchResults.isEmpty()) {
            return Map.of();
        }

        Map<String, ConsensusResult.Vote> votes = new HashMap<>();

        for (BranchResult br : branchResults) {
            String branchId = br.getBranchId();
            String output =
                    br.getResult().getOutput() != null ? br.getResult().getOutput().toString() : "";

            Map<String, Object> yields = br.yields();
            double score = readScore(yields);
            ConsensusResult.VoteType voteType =
                    readApproval(yields, score, config.getThreshold())
                            ? ConsensusResult.VoteType.APPROVE
                            : ConsensusResult.VoteType.REJECT;

            votes.put(
                    branchId,
                    new ConsensusResult.Vote(
                            branchId, branchId, voteType, score, br.weight(), output));
        }

        return votes;
    }

    // -- Structured yield readers ------------------------------------------------

    /// Reads the numerical score from the yields map.
    ///
    /// @param yields extracted branch yields, not null
    /// @return the score value, or 0.0 if absent or not a number
    private double readScore(Map<String, Object> yields) {
        Object value = yields.get(EngineVariables.SCORE);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    /// Reads the approval flag from the yields map.
    ///
    /// If {@code approved} is present as a boolean, returns it directly.
    /// Otherwise, derives approval from score vs threshold – this covers
    /// the case where the agent produced a score but omitted the explicit
    /// approval field.
    ///
    /// @param yields extracted branch yields, not null
    /// @param score the extracted score value
    /// @param threshold configured threshold, may be null (uses default)
    /// @return true if the branch approves
    private boolean readApproval(Map<String, Object> yields, double score, Double threshold) {
        Object value = yields.get(EngineVariables.APPROVED);
        if (value instanceof Boolean b) {
            return b;
        }
        double effectiveThreshold = threshold != null ? threshold : DEFAULT_THRESHOLD;
        return score >= effectiveThreshold;
    }

    // -- Strategy evaluators -----------------------------------------------------

    /// Evaluates consensus using majority vote strategy.
    ///
    /// Consensus is reached when the number of approvals **strictly exceeds**
    /// the threshold ratio of total votes. At 50% threshold, 2/4 does NOT pass.
    ///
    /// Vote-based strategies do not compute winners – the vote gates the
    /// transition path, ALL branch yields merge regardless of individual vote.
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
        boolean consensusReached = approveCount > (long) (total * effectiveThreshold);

        String reasoning =
                String.format(
                        "Majority vote: %d approve, %d reject out of %d total"
                                + " (threshold=%.0f%%, required>%d). %s",
                        approveCount,
                        rejectCount,
                        total,
                        effectiveThreshold * 100,
                        (long) (total * effectiveThreshold),
                        consensusReached ? "Consensus reached." : "Consensus not reached.");

        return ConsensusResult.builder()
                .consensusReached(consensusReached)
                .strategyUsed(config.getStrategy())
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus using unanimous vote strategy.
    ///
    /// Consensus is reached only when every branch approves. Returns
    /// {@code consensusReached = false} for empty vote maps since
    /// {@link java.util.stream.Stream#allMatch} returns true on empty streams.
    ///
    /// @param votes map of branch votes, not null
    /// @param config consensus configuration, not null
    /// @return consensus result, never null
    private ConsensusResult evaluateUnanimous(
            Map<String, ConsensusResult.Vote> votes, ConsensusConfig config) {

        boolean allApprove =
                !votes.isEmpty()
                        && votes.values().stream().allMatch(ConsensusResult.Vote::isApprove);

        String reasoning;
        if (votes.isEmpty()) {
            reasoning = "Unanimous not reached: no branches to evaluate.";
        } else if (allApprove) {
            reasoning = "Unanimous approval: all " + votes.size() + " branches approved.";
        } else {
            long rejectCount =
                    votes.values().stream().filter(ConsensusResult.Vote::isReject).count();
            reasoning =
                    String.format("Unanimous not reached: %d branch(es) rejected.", rejectCount);
        }

        return ConsensusResult.builder()
                .consensusReached(allApprove)
                .strategyUsed(config.getStrategy())
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus using weighted vote strategy.
    ///
    /// Weighted approve ratio must meet or exceed the threshold.
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
        double totalWeighted = weightedApprove + weightedReject;
        double approveRatio = totalWeighted > 0 ? weightedApprove / totalWeighted : 0;

        boolean consensusReached = approveRatio >= threshold;

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
                .votes(votes)
                .reasoning(reasoning)
                .build();
    }

    /// Evaluates consensus by invoking a judge agent.
    ///
    /// Unlike vote-based strategies, JUDGE_DECIDES skips {@link #extractVotes} entirely –
    /// branches don't self-score, so the judge sees only branch outputs and IDs.
    ///
    /// Fires {@link ExecutionListener#onAgentStart} and {@link ExecutionListener#onAgentComplete}
    /// so the judge call is visible in CLI verbose output and observability.
    ///
    /// @param branchResults original branch results for judge context, not null
    /// @param config consensus configuration with judge agent ID, not null
    /// @param state workflow state for agent context, not null
    /// @param agentRegistry registry for looking up judge agent, not null
    /// @param listener execution listener for agent lifecycle events, not null
    /// @return consensus result based on judge decision, never null
    /// @throws IllegalStateException if judge agent ID is missing or agent not found
    private ConsensusResult evaluateJudgeDecides(
            List<BranchResult> branchResults,
            ConsensusConfig config,
            HensuState state,
            AgentRegistry agentRegistry,
            ExecutionListener listener) {

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

        Set<String> validBranchIds =
                branchResults.stream().map(BranchResult::getBranchId).collect(Collectors.toSet());
        String prompt = buildJudgePrompt(branchResults, validBranchIds);

        logger.info("Invoking judge agent: " + judgeAgentId);
        listener.onAgentStart("consensus/judge", judgeAgentId, prompt);
        AgentResponse judgeResponse = judgeAgent.execute(prompt, state.getContext());
        listener.onAgentComplete("consensus/judge", judgeAgentId, judgeResponse);

        String responseContent =
                switch (judgeResponse) {
                    case AgentResponse.TextResponse t -> t.content();
                    case AgentResponse.Error e ->
                            throw new IllegalStateException("Judge agent failed: " + e.message());
                    default ->
                            throw new IllegalStateException(
                                    "Unexpected response type from judge agent");
                };

        Optional<String> violation = AgentOutputValidator.validate(responseContent);
        if (violation.isPresent()) {
            throw new IllegalStateException("Judge agent output " + violation.get());
        }

        return parseJudgeResponse(responseContent, config, validBranchIds);
    }

    /// Builds the prompt for the judge agent.
    ///
    /// The judge is asked only to decide (approve/reject), pick a winner, and
    /// explain. It does NOT produce a {@code final_output} – the winning
    /// branch's yields serve as the domain output.
    ///
    /// @param branchResults original branch results, not null
    /// @param validBranchIds set of valid branch IDs the judge may select, not null
    /// @return formatted judge prompt, never null
    private String buildJudgePrompt(List<BranchResult> branchResults, Set<String> validBranchIds) {

        StringBuilder sb = new StringBuilder();
        sb.append(
                """
                You are the judge for a consensus decision. \
                Review the following branch results and make a final decision.

                """);
        sb.append("## Branch Results:\n\n");

        for (BranchResult br : branchResults) {
            sb.append(String.format("### Branch: %s\n", br.getBranchId()));
            sb.append("Output:\n```\n");
            sb.append(br.getResult().getOutput());
            sb.append("\n```\n\n");
        }

        sb.append("## Valid branch IDs: ").append(validBranchIds).append("\n\n");

        sb.append(
                """
                ## Your Task:
                1. Analyze all branch outputs
                2. Determine if the overall result should be approved or rejected
                3. Select the winning branch

                Respond with a JSON object containing ONLY these fields:
                - "decision": true if approved, false if rejected
                - "winning_branch": one of the valid branch IDs listed above
                - "reasoning": brief explanation of your decision
                """);

        return sb.toString();
    }

    /// Parses the judge agent's response into a ConsensusResult.
    ///
    /// Uses {@link JsonUtil#extractOutputParams} – the same extraction path as
    /// {@link io.hensu.core.execution.pipeline.OutputExtractionPostProcessor}.
    /// Validates that the judge's {@code winning_branch} is one of the actual
    /// branch IDs to prevent ghost-winner bugs.
    ///
    /// @param judgeOutput the judge's response text (already validated), not null
    /// @param config consensus configuration, not null
    /// @param validBranchIds set of actual branch IDs, not null
    /// @return parsed consensus result, never null
    private ConsensusResult parseJudgeResponse(
            String judgeOutput, ConsensusConfig config, Set<String> validBranchIds) {

        Map<String, Object> fields = new HashMap<>();
        JsonUtil.extractOutputParams(JUDGE_FIELDS, judgeOutput, fields, logger);

        boolean consensusReached = fields.get(JUDGE_DECISION) instanceof Boolean b && b;

        String winningBranch = fields.get(JUDGE_WINNING_BRANCH) instanceof String s ? s : null;

        if (winningBranch != null && !validBranchIds.contains(winningBranch)) {
            logger.warning(
                    "Judge returned unknown winning_branch '"
                            + winningBranch
                            + "', valid IDs: "
                            + validBranchIds);
            winningBranch = null;
        }

        String reasoning =
                fields.get(JUDGE_REASONING) instanceof String s
                        ? s
                        : "Judge decision: " + (consensusReached ? "approved" : "rejected");

        return ConsensusResult.builder()
                .consensusReached(consensusReached)
                .strategyUsed(config.getStrategy())
                .winningBranchId(winningBranch)
                .finalOutput(judgeOutput)
                .votes(Map.of())
                .reasoning(reasoning)
                .build();
    }
}
