/**
 * Example demonstrating multi-tier LLM-based rubric evaluation.
 *
 * Scenarios (via stub_scenario in context):
 *   default    - Normal evaluation (score ~78)
 *   strict     - Strict evaluator (score ~45, triggers revision)
 *   lenient    - Lenient evaluator (score ~95, quick approval)
 */
fun llmEvalTestWorkflow() = workflow("llm-eval-test") {
    description = "Example of multi-tier rubric evaluation with score-based routing"
    version = "1.0.0"

    state {
        input("topic", VarType.STRING)
        variable("article",      VarType.STRING, "the full written article text")
        variable("improvements", VarType.STRING, "specific improvements recommended by the evaluator")
        variable("reasoning",    VarType.STRING, "evaluator's reasoning for the assigned score")
    }

    agents {
        agent("writer") {
            role = "Technical content writer. Return JSON with key: article."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.7
        }

        agent("evaluator") {
            role = "Content quality evaluator. Return JSON with keys: reasoning, improvements."
            model = Models.CLAUDE_OPUS_4_5
            temperature = 0.2
        }

        agent("editor") {
            role = "Editor who revises content. Return JSON with key: article."
            model = Models.GEMINI_3_1_PRO
            temperature = 0.5
        }
    }

    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = "Write a technical article about: {topic}. Include introduction, 3-4 main points, examples, and conclusion."
            writes("article")
            onSuccess goto "evaluate"
            onFailure retry 2 otherwise "failed"
        }

        node("evaluate") {
            agent = "evaluator"
            prompt = "Evaluate this article: {article}. Return JSON with reasoning and improvements."
            rubric = "content-quality.md"
            writes("improvements", "reasoning")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "approve"
                whenScore lessThan 60.0 goto "rewrite"
                // Middle band [60, 80): a closed range 60.0..79.0 would leave
                // fractional scores in (79, 80) unmatched — the else-arm is gap-free.
                otherwise revise "revise" retry 2 otherwise "escalate"
            }
        }

        node("approve") {
            agent = "editor"
            prompt = "Article approved (score: {score}). Content: {article}. Make final polish edits."
            writes("article")
            onSuccess goto "published"
        }

        node("revise") {
            agent = "editor"
            prompt = "Revise article: {article}. Score: {score}. Improvements needed: {improvements}. Reasoning: {reasoning}"
            writes("article")
            onSuccess goto "evaluate"
            onFailure retry 2 otherwise "escalate"
        }

        node("rewrite") {
            agent = "writer"
            prompt = "Article scored poorly ({score}). Rewrite about: {topic}. Issues: {improvements}"
            writes("article")
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
