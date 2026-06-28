/**
 * Two-agent content pipeline from the Hensu README example.
 *
 * Flow: writer drafts an article → rubric scores it (retry if < 70) →
 * reviewer approves (done) or rejects (revise back to writer, up to 3 times).
 *
 * Run: hensu run content-pipeline -d working-dir -v -c "{\"topic\": \"Cold Fusion\"}"
 *      -v (verbose) shows node inputs and outputs in the console
 */
fun contentPipeline() = workflow("content-pipeline") {
    description = "Two-agent content pipeline: writer drafts, reviewer approves or loops back"
    version = "1.0.0"

    agents {
        agent("writer")   { role = "Content Writer";   model = Models.GEMINI_3_1_FLASH_LITE }
        agent("reviewer") { role = "Content Reviewer"; model = Models.GEMINI_3_1_FLASH_LITE }
    }

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
            rubric = "content-quality.md"
            onScore {
                whenScore lessThan 70.0 goto "write" withFeedback
            }
            onSuccess goto "review"
        }

        node("review") {
            agent  = "reviewer"
            prompt = "Review this article as it must be about dogs: {draft}."
            writes("draft")
            onApproval  goto "done"
            onRejection revise "write" withFeedback retry 3 otherwise "needs-work" withFeedback
        }

        end("done", ExitStatus.SUCCESS)
        end("needs-work", ExitStatus.FAILURE)
    }
}
