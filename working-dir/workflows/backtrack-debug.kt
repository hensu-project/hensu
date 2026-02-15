/**
 * Backtracking Debug Workflow
 *
 * This workflow demonstrates Hensu's backtracking capabilities:
 *
 * 1. AUTO-BACKTRACKING (rubric-based):
 *    - Score < 30: Goes back to earliest logical step
 *    - Score 30-50: Goes back to previous phase
 *
 * 2. MANUAL BACKTRACKING (human review):
 *    - When review { allowBacktrack = true } is set
 *    - Human can choose to go back to any previous step
 *
 * Flow:
 *   [draft] -> [review] -> [refine] -> [final_check] -> [approve/reject]
 *              ^                              |
 *              |______ backtrack if fails ____|
 */
fun backtrackDebugWorkflow() = workflow("BacktrackDebug") {
    description = "Debug workflow demonstrating score-based backtracking"
    version = "1.0.0"

    agents {
        agent("writer") {
            role = "Content Writer"
            model = Models.CLAUDE_SONNET_4_5
        }
        agent("critic") {
            role = "Quality Reviewer"
            model = Models.CLAUDE_SONNET_4_5
        }
    }

    rubrics {
        rubric("content-quality", "content-quality.md")
    }

    graph {
        start at "draft"

        node("draft") {
            agent = "writer"
            prompt = "Write a short article about the topic in context."
            onSuccess goto "review"
        }

        node("review") {
            agent = "critic"
            prompt = "Review the draft and evaluate its quality."
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 70.0 goto "refine"
                whenScore lessThan 70.0 goto "draft"
            }
        }

        node("refine") {
            agent = "writer"
            prompt = "Refine the draft based on feedback."
            onSuccess goto "final_check"
        }

        node("final_check") {
            agent = "critic"
            prompt = "Final quality check on the refined content."
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "approve"
                whenScore greaterThanOrEqual 50.0 goto "refine"
                whenScore lessThan 50.0 goto "draft"
            }
        }

        end("approve", ExitStatus.SUCCESS)
        end("reject", ExitStatus.FAILURE)
    }
}
