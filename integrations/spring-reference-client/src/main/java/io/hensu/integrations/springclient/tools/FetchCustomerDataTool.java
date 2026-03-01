package io.hensu.integrations.springclient.tools;

import io.hensu.integrations.springclient.mcp.ToolHandler;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Mock MCP tool: fetches customer financial profile from CRM.
///
/// Returns a borderline fictional customer profile — good account standing overall but
/// with a few moderate risk signals. The data is intentionally ambiguous so
/// the LLM analyst cannot trivially approve or reject, making the human review
/// node genuinely meaningful.
///
/// All data is entirely fictional and for demonstration purposes only.
///
/// ### Profile design rationale
/// - Credit score 685: above subprime (660) but below prime (700) threshold
/// - Account age 2 years: established but not long-tenured
/// - Payment history 94%: one or two late payments in recent history
/// - Revenue $890k: growing SMB but below $1M milestone
/// - Existing limit $30k, requesting +$25k: 83% proportional increase is aggressive
///
/// Together these signals land squarely in "MEDIUM risk / REVIEW required" territory —
/// the agent will reason about trade-offs rather than pattern-match to an obvious outcome.
@Component
public class FetchCustomerDataTool implements ToolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FetchCustomerDataTool.class);

    @Override
    public String name() {
        return "fetch_customer_data";
    }

    @Override
    public String description() {
        return "Fetches the full financial profile of a customer from the CRM system, "
                + "including credit score, account age, payment history, revenue, "
                + "and current credit limits. Call this first before risk scoring.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolHandler.schema(
                Map.of("customerId", ToolHandler.stringParam("Unique customer identifier")),
                List.of("customerId"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments) {
        String customerId = (String) arguments.getOrDefault("customerId", "unknown");
        LOG.info("Fetching customer data: customerId={}", customerId);

        return Map.ofEntries(
                Map.entry("customerId", customerId),
                Map.entry("companyName", "Acme SaaS Corp"),
                Map.entry("industry", "B2B SaaS"),
                Map.entry("creditScore", 685),
                Map.entry("creditScoreRating", "Fair (subprime boundary)"),
                Map.entry("accountAgeDays", 730),
                Map.entry("paymentHistoryScore", 0.94),
                Map.entry("latePaymentsLast12Months", 1),
                Map.entry("annualRevenue", 890_000),
                Map.entry("existingCreditLimit", 30_000),
                Map.entry("requestedCreditIncrease", 25_000),
                Map.entry("outstandingBalance", 18_400),
                Map.entry("utilizationRate", 0.61));
    }
}
