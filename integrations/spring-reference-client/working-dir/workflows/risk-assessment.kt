/**
 * Credit limit increase evaluation workflow.
 *
 * Demonstrates dynamic MCP tool use combined with a mandatory human review gate.
 *
 * ### Execution flow
 *
 * ```
 * +——————————————————————+      tools/list (SSE)     +————————————————————————+
 * │  analyze-application │ ————————————————————————> │  spring-reference-     │
 * │  (LlmPlanner)        │                           │  client (MCP transport)│
 * │                      │  tools/call × 2 (SSE)     │                        │
 * │                      │ ————————————————————————> │  fetch_customer_data   │
 * │                      │ ————————————————————————> │  calculate_risk_score  │
 * +——————————————————————+                           +————————————————————————+
 *          │
 *          │ execution.paused (SSE → operator)
 *          V
 * +—————————————————————+
 * │   human reviewer    │  POST /demo/review/{id}  { approved: true/false }
 * +—————————————————————+
 *          │
 *    approved  │  declined
 *        V           V
 *    approved     rejected
 * ```
 *
 * ### Context variables
 * The caller must supply:
 * - `customerId`   — customer identifier forwarded to both tools
 * - `requestType`  — e.g. `credit-limit-increase`
 */
fun riskAssessmentWorkflow() = workflow("risk-assessment") {
    description = "Credit limit increase evaluation with AI analysis and mandatory human review"
    version = "1.0.0"

    state {
        input("customerId", VarType.STRING)
        input("requestType", VarType.STRING)
        variable("positiveFactors", VarType.STRING, "Positive factors supporting approval")
        variable("riskFactors", VarType.STRING, "Risk factors supporting caution or decline")
        variable("conditions", VarType.STRING, "If CONDITIONAL APPROVAL: the adjusted credit amount and any conditions attached")
        variable("summary", VarType.STRING, "A concise summary paragraph written for a credit committee reviewer")
    }

    agents {
        agent("analyst") {
            role = "Senior credit risk analyst specialising in SMB lending decisions"
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.2
            tools = listOf("fetch_customer_data", "calculate_risk_score")
        }
    }

    graph {
        start at "analyze-application"

        node("analyze-application") {
            agent = "analyst"
            writes("positiveFactors", "riskFactors", "conditions", "summary")
            prompt = """
                You are a senior credit risk analyst evaluating a credit limit increase request.

                Customer ID : {customerId}
                Request type: {requestType}

                Retrieve the customer's financial profile, compute their risk score,
                and synthesise a recommendation covering positive factors, risk factors,
                any conditions, and a summary paragraph for the credit committee.
            """.trimIndent()

            review {
                mode = ReviewMode.REQUIRED
                allowEdit = true
                allowBacktrack = false
            }

            onApproval goto "approved"
            onRejection goto "rejected"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
