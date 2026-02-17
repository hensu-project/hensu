/**
 * Test workflow demonstrating LLM-based evaluation.
 *
 * Test scenarios (via stub_scenario in context):
 *   default    - Normal evaluation (score ~78)
 *   strict     - Strict evaluator (score ~45, triggers revision)
 *   lenient    - Lenient evaluator (score ~95, quick approval)
 */
fun llmEvalTestWorkflow() = workflow("llm-eval-test") {
    description = "Workflow with LLM-based rubric evaluation"
    version = "1.0.0"

    agents {
        agent("writer") {
            role = "Technical content writer"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.7
        }

        agent("evaluator") {
            role = "Content quality evaluator"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.2
        }

        agent("editor") {
            role = "Editor who revises content"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.5
        }
    }

    rubrics {
        rubric("content_quality", "content-quality.md")
    }

    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = "Write a technical article about: {topic}. Include introduction, 3-4 main points, examples, and conclusion."
            onSuccess goto "evaluate"
            onFailure retry 2 otherwise "failed"
        }

        node("evaluate") {
            agent = "evaluator"
            prompt = "Evaluate this article: {write}. Return JSON with score (0-100), reasoning, and improvements array."
            rubric = "content_quality"
            outputParams = listOf("score", "improvements", "reasoning")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "approve"
                whenScore `in` 60.0..79.0 goto "revise"
                whenScore lessThan 60.0 goto "rewrite"
            }
        }

        node("approve") {
            agent = "editor"
            prompt = "Article approved (score: {score}). Content: {write}. Make final polish edits."
            onSuccess goto "published"
        }

        node("revise") {
            agent = "editor"
            prompt = "Revise article: {write}. Score: {score}. Improvements needed: {improvements}. Reasoning: {reasoning}"
            onSuccess goto "evaluate"
            onFailure retry 2 otherwise "escalate"
        }

        node("rewrite") {
            agent = "writer"
            prompt = "Article scored poorly ({score}). Rewrite about: {topic}. Issues: {improvements}"
            onSuccess goto "evaluate"
            onFailure retry 1 otherwise "failed"
        }

        node("escalate") {
            agent = "writer"
            prompt = "Multiple revisions failed for: {topic}. Score: {score}. Summarize challenges."
            onSuccess goto "needs_review"
        }

        end("published", ExitStatus.SUCCESS)
        end("needs_review", ExitStatus.SUCCESS)
        end("failed", ExitStatus.FAILURE)
    }
}