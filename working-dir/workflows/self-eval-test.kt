/**
 * Test Workflow: Self-Evaluation and LLM Evaluation
 *
 * Tests the rubric evaluation system including:
 * - Self-evaluation: Agent returns its own score and recommendations
 * - Auto-backtracking: Automatic retry when scores are low
 * - Score-based transitions: Different paths based on evaluation scores
 *
 * Usage:
 *   ./hensu run -d working-dir self-eval-test.kt --context '{"topic": "AI Safety", "stub_scenario": "low_score"}'
 *
 * Scenarios (set via stub_scenario in context):
 *   - "default"     : Normal flow, scores pass (85+)
 *   - "low_score"   : Low scores trigger backtracking
 *   - "high_score"  : High scores, fast path to success
 *   - "self_eval"   : Focus on self-evaluation with recommendations
 *   - "backtrack"   : Critical failure, backtrack to start
 */

fun selfEvalTestWorkflow() = workflow("self-eval-test") {
    description = "Test workflow for self-evaluation and LLM-based evaluation"
    version = "1.0.0"

    agents {
        // Main content writer - provides self-evaluation in output
        agent("writer") {
            role = "Content writer who produces articles and self-evaluates. Include self-evaluation JSON with score (0-100) and recommendation if score < 80."
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.7
        }

        // Reviewer agent - evaluates content independently
        agent("reviewer") {
            role = "Content reviewer providing score and feedback"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.3
        }

        // Editor for improvements
        agent("editor") {
            role = "Editor who improves content based on recommendations"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.5
        }
    }

    rubrics {
        rubric("content_quality", "content-quality.md")
    }

    graph {
        start at "draft"

        // Step 1: Initial draft with self-evaluation
        node("draft") {
            agent = "writer"
            prompt = "Write about: {topic}. {recommendations}. Requirements: 1) Clear introduction, 2) At least 3 main points, 3) Concrete examples, 4) Conclusion. Include self-evaluation JSON: {\"score\": <0-100>, \"recommendation\": \"<how to improve>\"}"
            outputParams = listOf("score", "recommendation")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "review"
                whenScore `in` 50.0..79.0 goto "improve"
                whenScore lessThan 50.0 goto "restart"
            }
        }

        // Step 2: Review by independent agent
        node("review") {
            agent = "reviewer"
            prompt = "Review this content: {draft}. Provide JSON: {\"score\": <0-100>, \"recommendation\": \"<feedback>\", \"approved\": <true/false>}"
            outputParams = listOf("score", "recommendation", "approved")
            rubric = "content_quality"

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "finalize"
                whenScore `in` 60.0..79.0 goto "improve"
                whenScore lessThan 60.0 goto "draft"
            }
        }

        // Step 3: Improvement based on feedback
        node("improve") {
            agent = "editor"
            prompt = "Improve this content: {draft}. Feedback: {recommendations}. Include self-evaluation JSON after editing."
            outputParams = listOf("score", "recommendation")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "review"
                whenScore lessThan 80.0 goto "improve"
            }

            onFailure retry 3 otherwise "escalate"
        }

        // Step 4: Restart from scratch
        node("restart") {
            agent = "writer"
            prompt = "Previous attempt failed. Starting fresh. Topic: {topic}. Issues: {recommendations}. Create a new draft with self-evaluation."
            outputParams = listOf("score", "recommendation")

            onScore {
                whenScore greaterThanOrEqual 70.0 goto "review"
                whenScore lessThan 70.0 goto "fail"
            }
        }

        // Step 5: Finalize
        node("finalize") {
            agent = "editor"
            prompt = "Finalize this approved content: {review}. Make final edits and format for publication."
            onSuccess goto "success"
        }

        // Escalation node
        node("escalate") {
            agent = "writer"
            prompt = "Summarize issues and suggest next steps."
            onSuccess goto "needs_review"
        }

        // End nodes
        end("success", ExitStatus.SUCCESS)
        end("needs_review", ExitStatus.SUCCESS)
        end("fail", ExitStatus.FAILURE)
    }
}
