package io.hensu.integrations.springclient.tools;

import io.hensu.integrations.springclient.mcp.ToolHandler;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Mock MCP tool: calculates a composite credit risk score.
///
/// Combines credit score, utilization, payment history, and account tenure
/// into a single risk band. For the demo customer (C-42) this produces a
/// MEDIUM / REVIEW outcome — not an obvious approval or rejection.
///
/// ### Score derivation for C-42 (credit-limit-increase)
/// - Credit score 685 → base score 62 (fair boundary penalty)
/// - 61% utilization → −5 (high utilization against existing limit)
/// - 94% payment history → +4 (good but one recent miss)
/// - 2-year account → +1 (moderate tenure)
/// - 83% proportional increase requested → −2 (aggressive ask)
/// - Final: 60 → MEDIUM band → "REVIEW"
///
/// The agent sees competing positive signals (revenue growth, mostly clean history)
/// against negative signals (below-prime score, high utilization, aggressive increase)
/// and must weigh them — producing a substantive recommendation for the human reviewer.
@Component
public class CalculateRiskScoreTool implements ToolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CalculateRiskScoreTool.class);

    @Override
    public String name() {
        return "calculate_risk_score";
    }

    @Override
    public String description() {
        return "Calculates a composite credit risk score (0–100) for a customer and request type. "
                + "Returns a risk band (LOW/MEDIUM/HIGH), a recommendation (APPROVE/REVIEW/DECLINE), "
                + "and a breakdown of contributing factors. Call after fetch_customer_data.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolHandler.schema(
                Map.of(
                        "customerId", ToolHandler.stringParam("Unique customer identifier"),
                        "requestType", ToolHandler.stringParam(
                                "Type of credit request: credit-limit-increase, "
                                        + "new-credit-line, or emergency-overdraft")),
                List.of("customerId", "requestType"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments) {
        String customerId = (String) arguments.getOrDefault("customerId", "unknown");
        String requestType = (String) arguments.getOrDefault("requestType", "unknown");

        LOG.info("Calculating risk score: customerId={}, requestType={}", customerId, requestType);

        return Map.ofEntries(
                Map.entry("customerId", customerId),
                Map.entry("requestType", requestType),
                Map.entry("riskScore", 60),
                Map.entry("riskBand", "MEDIUM"),
                Map.entry("recommendation", "REVIEW"),
                Map.entry("scoreBreakdown", Map.of(
                        "creditScoreComponent", 62,
                        "utilizationPenalty", -5,
                        "paymentHistoryBonus", 4,
                        "tenureBonus", 1,
                        "requestSizePenalty", -2)),
                Map.entry("riskFlags", List.of(
                        "Credit score 15 points below prime threshold",
                        "Utilization at 61% — above 60% warning band",
                        "Requested increase is 83% of existing limit")),
                Map.entry("positiveFactors", List.of(
                        "Revenue trajectory: B2B SaaS sector showing growth",
                        "Only one late payment in 12 months",
                        "No missed payments in last 6 months")),
                Map.entry("analystNote",
                        "Borderline case. Score does not support automatic approval "
                                + "but positive trajectory may warrant conditional increase "
                                + "at reduced amount ($15k instead of $25k)."));
    }
}
