/**
 * Two-agent content pipeline from the Hensu README example.
 *
 * Flow: writer drafts an article → rubric scores it (retry if < 70) →
 * reviewer approves (done) or rejects (loop back to writer).
 *
 * Run: hensu run content-pipeline -d working-dir -v -c "{\"topic\": \"Cold Fusion\"}"
 *      -v (verbose) shows node inputs and outputs in the console
 */
fun contentPipeline() = workflow("content-pipeline") {
    description = "Two-agent content pipeline: writer drafts, reviewer approves or loops back"
    version = "1.0.0"

    agents {
        agent("writer")   { role = "Content Writer";   model = Models.GEMINI_3_1_FLASH_LITE }
        agent("reviewer") { role = "Content Reviewer"; model = Models.CLAUDE_SONNET_4_6 }
    }

    rubrics { rubric("content-quality", "content-quality.md") }

    state {
        input("topic", VarType.STRING)
        variable("draft", VarType.STRING, "the full written article text")
    }

    graph {
        start at "write"

        node("write") {
            agent  = "writer"
            prompt = "Write a short article about {topic}."
            writes("draft")
            rubric = "content-quality"
            onScore {
                whenScore lessThan 70.0 goto "write"
            }
            onSuccess goto "review"
        }

        node("review") {
            agent  = "reviewer"
            prompt = "Review this article: {draft}. Is it good enough to publish?"
            writes("draft")
            onApproval  goto "done"
            onRejection goto "write"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
