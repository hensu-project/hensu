/**
 * Example of consensus-based parallel execution.
 *
 * This workflow demonstrates:
 * - Parallel execution of multiple reviewers
 * - Majority vote consensus strategy
 * - Score-based voting determination
 */
fun workflow() = workflow("consensus-test") {
    description = "Example of consensus-based parallel execution"

    agents {
        agent("writer") {
            model = "stub"
            role = "Content writer"
        }
        agent("reviewer1") {
            model = "stub"
            role = "Reviewer focusing on clarity"
        }
        agent("reviewer2") {
            model = "stub"
            role = "Reviewer focusing on accuracy"
        }
        agent("reviewer3") {
            model = "stub"
            role = "Reviewer focusing on completeness"
        }
    }

    state {
        variable("content", VarType.STRING, "the written paragraph to review")
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

        // Parallel review with consensus
        parallel("review") {
            branch("clarity_review") {
                agent = "reviewer1"
                prompt = """
                    Review the following content for clarity:

                    {content}

                    Provide your assessment with a score (0-100) and decision.
                """.trimIndent()
            }

            branch("accuracy_review") {
                agent = "reviewer2"
                prompt = """
                    Review the following content for accuracy:

                    {content}

                    Provide your assessment with a score (0-100) and decision.
                """.trimIndent()
            }

            branch("completeness_review") {
                agent = "reviewer3"
                prompt = """
                    Review the following content for completeness:

                    {content}

                    Provide your assessment with a score (0-100) and decision.
                """.trimIndent()
            }

            consensus {
                strategy = ConsensusStrategy.MAJORITY_VOTE
                threshold = 0.5
            }

            onConsensus goto "approved"
            onNoConsensus goto "rejected"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
