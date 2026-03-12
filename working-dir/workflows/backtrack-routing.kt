/**
 * Backtracking Routing Workflow
 *
 * Demonstrates auto-backtracking via rubric score thresholds:
 *
 * - Score >= 70 at review  → proceed to refine
 * - Score <  70 at review  → backtrack to draft
 * - Score >= 80 at final   → approve
 * - Score 50–79 at final   → backtrack to refine
 * - Score <  50 at final   → backtrack all the way to draft
 *
 * Flow:
 *   [draft] -> [review] -> [refine] -> [final_check] -> [approve]
 *              ^    |                       |    |
 *              |____|_______backtrack_______|    |
 *                                                V
 *                                            [reject]
 */
fun backtrackRoutingWorkflow() = workflow("backtrack-routing") {
    description = "Auto-backtracking workflow driven by rubric score thresholds"
    version = "1.0.0"

    state {
        input("topic", VarType.STRING)
        variable("article", VarType.STRING, "the full written article text")
    }

    agents {
        agent("writer") {
            role = "Content Writer"
            model = Models.CLAUDE_HAIKU_4_5
        }
        agent("critic") {
            role = "Quality Reviewer. Return JSON with key: recommendation."
            model = Models.GEMINI_3_1_PRO
        }
    }

    rubrics {
        rubric("content-quality", "content-quality.md")
    }

    graph {
        start at "draft"

        node("draft") {
            agent = "writer"
            prompt = "Write a short article about {topic}. {recommendation}"
            writes("article")
            onSuccess goto "review"
            onFailure retry 2 otherwise "reject"
        }

        node("review") {
            agent = "critic"
            prompt = "Review the following draft and evaluate its quality.\n\n{article}"
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 70.0 goto "refine"
                whenScore lessThan 70.0 goto "draft"
            }
            onFailure retry 1 otherwise "reject"
        }

        node("refine") {
            agent = "writer"
            prompt = "Refine the following draft based on the recommendation.\n\nDraft: {article}\nRecommendation: {recommendation}"
            writes("article")
            onSuccess goto "final_check"
            onFailure retry 2 otherwise "reject"
        }

        node("final_check") {
            agent = "critic"
            prompt = "Final quality check on the refined content.\n\n{article}"
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "approve"
                whenScore greaterThanOrEqual 50.0 goto "refine"
                whenScore lessThan 50.0 goto "draft"
            }
            onFailure retry 1 otherwise "reject"
        }

        end("approve", ExitStatus.SUCCESS)
        end("reject", ExitStatus.FAILURE)
    }
}
