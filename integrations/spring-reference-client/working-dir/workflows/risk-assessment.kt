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

    agents {
        agent("analyst") {
            role = "Senior credit risk analyst specialising in SMB lending decisions"
            model = Models.GEMINI_2_5_PRO
            temperature = 0.2
        }
    }

    graph {
        start at "analyze-application"

        node("analyze-application") {
            agent = "analyst"
            prompt = """
                You are a senior credit risk analyst evaluating a credit limit increase request.

                Customer ID : {customerId}
                Request type: {requestType}

                Use the available tools in this order:
                1. Call fetch_customer_data to retrieve the customer's full financial profile.
                2. Call calculate_risk_score to compute the composite risk score and breakdown.
                3. Synthesise a recommendation using both the raw data and the risk assessment.

                Your final output must contain:
                - Recommendation: APPROVE, CONDITIONAL APPROVAL, or DECLINE
                - Positive factors supporting approval
                - Risk factors supporting caution or decline
                - If CONDITIONAL APPROVAL: the adjusted credit amount and any conditions
                - A concise summary paragraph written for a credit committee reviewer
            """.trimIndent()

            planning {
                mode = PlanningMode.DYNAMIC
                maxSteps = 5
                maxDuration = Duration.ofMinutes(3)
            }

            review {
                mode = ReviewMode.REQUIRED
                allowEdit = true
                allowBacktrack = false
            }

            onSuccess goto "approved"
            onFailure goto "rejected"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
