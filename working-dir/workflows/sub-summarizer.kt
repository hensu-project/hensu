/**
 * Child sub-workflow: `sub-summarizer`.
 *
 * Invoked by `main-research.kt` via a `subWorkflow { }` node. State vars are
 * passed by name — the parent `imports("draft")` expects the child to declare
 * `draft` as an input, and `writes("tl_dr")` expects the child to write `tl_dr`.
 * No renaming; names are the contract.
 */
fun subSummarizer() = workflow("sub-summarizer") {
    description = "Summarizes a draft passed in from a parent workflow"
    version = "1.0.0"

    agents {
        agent("summarizer") {
            role  = "Technical Summarizer"
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.2
        }
    }

    state {
        // `draft` is populated by the parent via `imports("draft")`.
        input("draft", VarType.STRING)
        // `tl_dr` is read back by the parent via `writes("tl_dr")`.
        variable("tl_dr", VarType.STRING, "three-sentence summary of the draft")
    }

    graph {
        start at "summarize"

        node("summarize") {
            agent  = "summarizer"
            prompt = """
                Summarize the following article in exactly three sentences.
                Preserve the key claims. No preamble, no bullet points.

                ARTICLE:
                {draft}
            """.trimIndent()
            writes("tl_dr")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
