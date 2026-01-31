/**
 * Test workflow for human review mode with manual backtracking.
 *
 * This workflow demonstrates:
 * - Human review checkpoints at key steps
 * - Manual backtracking to any previous step
 * - Approve/Reject decisions
 */
fun workflow() = workflow("human-review-test") {
    description = "Test workflow for human review with manual backtracking"

    agents {
        agent("researcher") {
            model = "claude-sonnet-4-5-20250514"
            role = "Research analyst gathering information"
        }
        agent("writer") {
            model = "claude-sonnet-4-5-20250514"
            role = "Content writer creating drafts"
        }
        agent("editor") {
            model = "claude-sonnet-4-5-20250514"
            role = "Editor reviewing and improving content"
        }
    }

    graph {
        start at "research"

        // Step 1: Research phase
        node("research") {
            agent = "researcher"
            prompt = "Research the topic: {topic}"

            // Human review checkpoint - can backtrack to restart research
            review {
                mode = ReviewMode.OPTIONAL
                allowBacktrack = true
                allowEdit = false
            }

            onSuccess goto "draft"
        }

        // Step 2: Draft creation
        node("draft") {
            agent = "writer"
            prompt = """
                Based on the research:
                {research}

                Write a comprehensive article about {topic}.
            """.trimIndent()

            // Human review - can backtrack to research if draft needs more info
            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = true
            }

            onSuccess goto "edit"
        }

        // Step 3: Editing phase
        node("edit") {
            agent = "editor"
            prompt = """
                Review and improve this draft:
                {draft}

                Focus on clarity, structure, and completeness.
            """.trimIndent()

            // Human review - final approval before completion
            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = true
            }

            onSuccess goto "complete"
        }

        // End: Success
        end("complete", ExitStatus.SUCCESS)
    }
}
