/**
 * Example: Self-Evaluation and Rubric-Based Scoring
 *
 * Demonstrates the rubric evaluation system including:
 * - Self-evaluation: Agent returns its own score and recommendations
 * - Auto-backtracking: Automatic retry when scores are low
 * - Score-based transitions: Different paths based on evaluation scores
 *
 * Usage:
 *   ./hensu run -d working-dir self-evaluation.kt --context '{"topic": "AI Safety", "stub_scenario": "low_score"}'
 *
 * Scenarios (set via stub_scenario in context):
 *   - "default"     : Normal flow, scores pass (85+)
 *   - "low_score"   : Low scores trigger backtracking
 *   - "high_score"  : High scores, fast path to success
 *   - "self_eval"   : Focus on self-evaluation with recommendations
 *   - "backtrack"   : Critical failure, backtrack to start
 */

fun selfEvaluationWorkflow() = workflow("self-evaluation") {
    description = "Example of self-evaluation and rubric-based scoring loops"
    version = "1.0.0"

    state {
        input("topic", VarType.STRING)
        variable("article",  VarType.STRING, "the full written article text")
        variable("summary",  VarType.STRING, "summary of issues when escalation is required")
    }

    agents {
        // Main content writer - provides self-evaluation in output
        agent("writer") {
            role = "Content writer who produces articles and self-evaluates. Return JSON with keys: article, recommendation."
            model = Models.GEMINI_2_5_PRO
            temperature = 0.7
        }

        // Reviewer agent - evaluates content independently
        agent("reviewer") {
            role = "Content reviewer. Return JSON with key: recommendation."
            model = Models.GEMINI_3_1_PRO
            temperature = 0.3
        }

        // Editor for improvements
        agent("editor") {
            role = "Editor who improves content based on recommendations. Return JSON with keys: article, recommendation."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.5
        }
    }

    rubrics {
        rubric("content-quality", "content-quality.md")
    }

    graph {
        start at "draft"

        // Step 1: Initial draft with self-evaluation
        node("draft") {
            agent = "writer"
            prompt = "Write about: {topic}. {recommendation}. Requirements: 1) Clear introduction, 2) At least 3 main points, 3) Concrete examples, 4) Conclusion."
            writes("article")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "review"
                whenScore `in` 50.0..79.0 goto "improve"
                whenScore lessThan 50.0 goto "restart"
            }
        }

        // Step 2: Review by independent agent
        node("review") {
            agent = "reviewer"
            prompt = "Review this content: {article}. Evaluate quality, accuracy, and completeness."
            rubric = "content-quality"

            // Critical failure overrides approval: score below 60 means restart draft
            onScore { whenScore lessThan 60.0 goto "draft" }
            onApproval goto "finalize"
            onRejection goto "improve"
        }

        // Step 3: Improvement based on feedback
        node("improve") {
            agent = "editor"
            prompt = "Improve this content: {article}. Feedback: {recommendation}."
            writes("article")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "review"
                whenScore lessThan 80.0 goto "improve"
            }

            onFailure retry 3 otherwise "escalate"
        }

        // Step 4: Restart from scratch
        node("restart") {
            agent = "writer"
            prompt = "Previous attempt failed. Starting fresh. Topic: {topic}. Issues: {recommendation}. Create a new draft."
            writes("article")

            onScore {
                whenScore greaterThanOrEqual 70.0 goto "review"
                whenScore lessThan 70.0 goto "fail"
            }
        }

        // Step 5: Finalize
        node("finalize") {
            agent = "editor"
            prompt = "Finalize this approved content: {article}. Apply final recommendation: {recommendation}. Format for publication."
            writes("article")
            onSuccess goto "success"
        }

        // Escalation node
        node("escalate") {
            agent = "writer"
            prompt = "Summarize issues and suggest next steps."
            writes("summary")
            onSuccess goto "needs_review"
        }

        // End nodes
        end("success", ExitStatus.SUCCESS)
        end("needs_review", ExitStatus.SUCCESS)
        end("fail", ExitStatus.FAILURE)
    }
}
