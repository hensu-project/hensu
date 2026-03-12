/**
 * Example of human review mode with manual backtracking.
 *
 * This workflow demonstrates:
 * - Human review checkpoints at key steps
 * - Manual backtracking to any previous step
 * - Approve/Reject decisions
 */
fun workflow() = workflow("human-review-test") {
    description = "Example of human review with manual backtracking"

    agents {
        agent("researcher") {
            model = Models.CLAUDE_SONNET_4_6
            role = "Research analyst gathering information"
        }
        agent("writer") {
            model = Models.CLAUDE_SONNET_4_6
            role = "Content writer creating drafts"
        }
        agent("editor") {
            model = Models.CLAUDE_SONNET_4_6
            role = "Editor reviewing and improving content"
        }
    }

    state {
        input("topic",    VarType.STRING)
        variable("research", VarType.STRING, "research findings on the topic")
        variable("draft",    VarType.STRING, "the written article draft")
        variable("article",  VarType.STRING, "the final edited article")
    }

    graph {
        start at "research"

        // Step 1: Research phase
        node("research") {
            agent = "researcher"
            prompt = "Research the topic: {topic}"
            writes("research")

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
            writes("draft")

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
            writes("article")

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
