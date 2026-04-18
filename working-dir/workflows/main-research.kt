/**
 * Parent workflow: `main-research`.
 *
 * Flow:
 *   1. `write_article`  – researcher drafts an article about {topic}
 *   2. `delegate_summary` – delegates to the `sub-summarizer` child workflow,
 *                           passing `draft` as `article_text` and receiving
 *                           `summary` back as `tl_dr`
 *   3. `publish`        – composes the final deliverable using both vars
 *
 * Run (once DSL + CLI support exist):
 *   hensu run main-research --with sub-summarizer -d working-dir -v -c "{\"topic\": \"Project Loom\"}"
 */
fun mainResearch() = workflow("main-research") {
    description = "Parent workflow that delegates summarization to a child sub-workflow"
    version = "1.0.0"

    agents {
        agent("researcher") { role = "Research Writer"; model = Models.GEMINI_3_1_PRO }
        agent("editor")     { role = "Publishing Editor"; model = Models.GEMINI_3_1_FLASH_LITE }
    }

    state {
        input("topic", VarType.STRING)
        variable("draft",     VarType.STRING, "full article draft produced by the researcher")
        variable("tl_dr",     VarType.STRING, "three-sentence summary returned by the sub-workflow")
        variable("published", VarType.STRING, "final publishable deliverable (title + tl;dr + body)")
    }

    graph {
        start at "write_article"

        // 1. Draft the article in the parent context.
        node("write_article") {
            agent  = "researcher"
            prompt = "Write a detailed 4-paragraph article about {topic}."
            writes("draft")
            onSuccess goto "delegate_summary"
        }

        // 2. Delegate to the child workflow.
        //
        // imports(...) – vars copied from parent state into the child context.
        //                Names must exist in both state schemas (same discipline
        //                as `writes` on a regular node — no renaming).
        // writes(...)  – mirrors what the sub-workflow writes. These vars land
        //                in parent state under the same names.
        subWorkflow("delegate_summary") {
            target = "sub-summarizer"

            imports("draft")
            writes("tl_dr")

            onSuccess goto "publish"
            onFailure goto "publish"   // fall through; editor will handle a missing tl_dr
        }

        // 3. Compose the final deliverable using parent + returned child var.
        node("publish") {
            agent  = "editor"
            prompt = """
                Compose a publishable post about {topic}.

                TL;DR (from summarizer):
                {tl_dr}

                FULL DRAFT:
                {draft}

                Output the final post with a title line, the TL;DR, then the body.
            """.trimIndent()
            writes("published")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
