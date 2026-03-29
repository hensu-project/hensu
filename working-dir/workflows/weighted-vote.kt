/**
 * Example of WEIGHTED_VOTE consensus strategy with yields.
 *
 * This workflow demonstrates:
 * - Parallel execution of reviewers with different weights
 * - Weighted vote consensus: weighted approval ratio must meet threshold
 * - Senior reviewer has higher weight (influence) than junior reviewers
 * - Branch yields: each reviewer produces domain feedback
 * - All yields merge into context regardless of vote outcome
 * - Downstream node incorporates all feedback
 *
 * Use WEIGHTED_VOTE when some reviewers carry more authority than others –
 * e.g. a senior engineer's review outweighs a junior's, or a domain
 * expert's opinion matters more than a generalist's.
 */
fun workflow() = workflow("weighted-vote-test") {
    description = "Weighted consensus: senior reviewer has more influence"

    agents {
        agent("writer") {
            model = Models.GEMINI_3_1_PRO
            role = "API documentation writer"
        }
        agent("junior1") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Junior reviewer focusing on readability"
        }
        agent("junior2") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Junior reviewer focusing on examples"
        }
        agent("senior") {
            model = Models.GEMINI_3_1_PRO
            role = "Senior API architect reviewing technical accuracy and completeness"
        }
        agent("editor") {
            model = Models.GEMINI_3_1_PRO
            role = "Technical editor who incorporates weighted review feedback"
        }
    }

    state {
        variable("api_docs", VarType.STRING, "the API documentation draft")
        variable("readability_feedback", VarType.STRING, "readability review feedback")
        variable("examples_feedback", VarType.STRING, "examples and usage review feedback")
        variable("architecture_feedback", VarType.STRING, "senior architect's technical review")
        variable("final_docs", VarType.STRING, "the revised API documentation")
    }

    graph {
        start at "write-docs"

        node("write-docs") {
            agent = "writer"
            prompt = "Write a short API endpoint documentation (2-3 sentences) for a POST /users endpoint that creates a new user. Include example"
            writes("api_docs")
            onSuccess goto "review"
        }

        parallel("review") {
            branch("readability") {
                agent = "junior1"
                weight = 0.3
                prompt = """
                    Review the following API documentation for readability:
                    {api_docs}

                    Is it clear and easy to follow for new developers?
                """.trimIndent()
                yields("readability_feedback")
            }

            branch("examples") {
                agent = "junior2"
                weight = 0.3
                prompt = """
                    Review the following API documentation for examples:
                    {api_docs}

                    Does it include sufficient usage examples?
                """.trimIndent()
                yields("examples_feedback")
            }

            branch("architecture") {
                agent = "senior"
                weight = 0.7
                prompt = """
                    Review the following API documentation for technical accuracy:
                    {api_docs}

                    Check for correct HTTP methods, status codes, and security considerations.
                """.trimIndent()
                yields("architecture_feedback")
            }

            consensus {
                strategy = ConsensusStrategy.WEIGHTED_VOTE
                threshold = 0.5
            }

            onConsensus goto "revise"
            onNoConsensus goto "rejected"
        }

        node("revise") {
            agent = "editor"
            prompt = """
                Revise the API documentation based on all reviewer feedback:

                Original: {api_docs}

                Readability feedback: {readability_feedback}
                Examples feedback: {examples_feedback}
                Senior architect feedback: {architecture_feedback}

                Produce an improved version addressing all feedback.
            """.trimIndent()
            writes("final_docs")
            onSuccess goto "approved"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
