/**
 * Example of consensus-based parallel execution with yields.
 *
 * This workflow demonstrates:
 * - Parallel execution of multiple reviewers
 * - Majority vote consensus strategy
 * - Branch yields: each reviewer produces domain feedback
 * - All branch yields merge into context (vote gates transition, not data)
 * - Downstream node consumes merged yields from all branches
 *
 * Note: yield field requirements are injected into the prompt automatically
 * by YieldsVariableInjector – no need to ask the LLM for specific fields.
 */
fun workflow() = workflow("consensus-test") {
    description = "Consensus-based parallel review with yields"

    agents {
        agent("writer") {
            model = Models.GEMINI_3_1_PRO
            role = "Content writer"
        }
        agent("reviewer1") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Reviewer focusing on clarity"
        }
        agent("reviewer2") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Reviewer focusing on accuracy"
        }
        agent("reviewer3") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Reviewer focusing on completeness"
        }
        agent("editor") {
            model = Models.GEMINI_3_1_PRO
            role = "Content editor who incorporates review feedback"
        }
    }

    state {
        variable("content", VarType.STRING, "the written paragraph to review")
        variable("clarity_feedback", VarType.STRING, "clarity reviewer's feedback on the content")
        variable("accuracy_feedback", VarType.STRING, "accuracy reviewer's feedback on the content")
        variable("completeness_feedback", VarType.STRING, "completeness reviewer's feedback on the content")
        variable("revised_content", VarType.STRING, "content revised based on reviewer feedback")
    }

    graph {
        start at "generate-content"

        // First generate content to review
        node("generate-content") {
            agent = "writer"
            prompt = "Write a short paragraph about the benefits of automated testing."
            writes("content")
            onSuccess goto "review"
        }

        // Parallel review with consensus – each branch yields domain feedback.
        // YieldsVariableInjector auto-injects the output field requirements.
        parallel("review") {
            branch("clarity_review") {
                agent = "reviewer1"
                prompt = "Review the following content for clarity: {content}"
                yields("clarity_feedback")
            }

            branch("accuracy_review") {
                agent = "reviewer2"
                prompt = "Review the following content for accuracy: {content}"
                yields("accuracy_feedback")
            }

            branch("completeness_review") {
                agent = "reviewer3"
                prompt = "Review the following content for completeness: {content}"
                yields("completeness_feedback")
            }

            consensus {
                strategy = ConsensusStrategy.MAJORITY_VOTE
                threshold = 0.5
            }

            onConsensus goto "revise"
            onNoConsensus goto "rejected"
        }

        // Downstream node consumes merged yields from winning branches
        node("revise") {
            agent = "editor"
            prompt = """
                Revise the following content based on reviewer feedback:

                Original: {content}

                Clarity feedback: {clarity_feedback}
                Accuracy feedback: {accuracy_feedback}
                Completeness feedback: {completeness_feedback}

                Produce an improved version addressing all feedback.
            """.trimIndent()
            writes("revised_content")
            onSuccess goto "approved"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
