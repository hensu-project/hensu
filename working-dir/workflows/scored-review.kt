/**
 * Demo workflow: write + review with quality scoring and human gate.
 */
fun workflow() = workflow("demo") {
    description = "Content creation with rubric scoring and human review"

    agents {
        agent("writer") {
            model = Models.CLAUDE_HAIKU_4_5
            role = "Content writer producing concise, well-structured articles"
        }
        agent("reviewer") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Content reviewer evaluating quality and providing feedback"
        }
    }

    state {
        input("topic", VarType.STRING)
        variable("article", VarType.STRING, "the written article")
        variable("review",  VarType.STRING, "the publication readiness review")
    }

    rubrics {
        rubric("content-quality", "content-quality.md")
    }

    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = """
                Write a focused article about: {topic}
                Keep it under 300 words. Be specific, avoid filler.
                If feedback was provided, incorporate it: {recommendation}
            """.trimIndent()
            writes("article")
            rubric = "content-quality"

            onScore {
                whenScore greaterThanOrEqual 70.0 goto "review"
                whenScore lessThan 70.0 goto "write"
            }
        }

        node("review") {
            agent = "reviewer"
            prompt = """
                Review this article for publication readiness:
                {article}
            """.trimIndent()
            writes("article", "review")

            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = true
            }

            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
