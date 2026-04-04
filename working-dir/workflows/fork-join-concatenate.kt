/**
 * Example: Fork-Join with CONCATENATE merge strategy
 *
 * This workflow demonstrates:
 * - Parallel content generation for different sections of a document
 * - CONCATENATE joins all successful branch outputs as formatted text separated by ---
 * - Useful when branches produce independent sections that form a single document
 *
 * Flow:
 *   start → outline → fork(intro, body, conclusion) → join → format → success
 *
 * Use case: Generate a technical blog post by writing each section in parallel,
 * then concatenating them into a single document for final formatting.
 */
fun workflow() = workflow("fork-join-concatenate") {
    description = "Demonstrates CONCATENATE merge – parallel section writing"

    agents {
        agent("planner") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Content planner"
        }
        agent("writer") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Technical writer"
        }
        agent("formatter") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Document formatter"
        }
    }

    state {
        variable("topic",          VarType.STRING, "blog post topic")
        variable("outline",        VarType.STRING, "structured outline for the blog post")
        variable("introduction",   VarType.STRING, "introduction section")
        variable("body",           VarType.STRING, "main body section")
        variable("conclusion",     VarType.STRING, "conclusion section")
        variable("combined_draft", VarType.STRING, "concatenated sections from all branches")
        variable("final_post",     VarType.STRING, "formatted final blog post")
    }

    graph {
        start at "plan-outline"

        node("plan-outline") {
            agent = "planner"
            prompt = """
                Create a structured outline for a technical blog post about:
                "Building fault-tolerant AI pipelines with structured concurrency"

                Include key points for introduction, main body (3 subsections), and conclusion.
            """.trimIndent()
            writes("topic", "outline")
            onSuccess goto "fork-sections"
        }

        fork("fork-sections") {
            targets("write-intro", "write-body", "write-conclusion")
            onComplete goto "join-sections"
        }

        node("write-intro") {
            agent = "writer"
            prompt = """
                Write the introduction section for a blog post about: {topic}

                Outline: {outline}

                Hook the reader, state the problem, and preview the solution.
                Write 2-3 paragraphs.
            """.trimIndent()
            writes("introduction")
            onSuccess goto "join-sections"
        }

        node("write-body") {
            agent = "writer"
            prompt = """
                Write the main body section for a blog post about: {topic}

                Outline: {outline}

                Cover the technical details with code examples and architecture diagrams.
                Write 4-5 paragraphs covering all subsections from the outline.
            """.trimIndent()
            writes("body")
            onSuccess goto "join-sections"
        }

        node("write-conclusion") {
            agent = "writer"
            prompt = """
                Write the conclusion section for a blog post about: {topic}

                Outline: {outline}

                Summarize key takeaways, provide next steps, and end with a call to action.
                Write 2-3 paragraphs.
            """.trimIndent()
            writes("conclusion")
            onSuccess goto "join-sections"
        }

        join("join-sections") {
            await("fork-sections")
            mergeStrategy = MergeStrategy.CONCATENATE
            exports("introduction", "body", "conclusion")
            writes("combined_draft")
            timeout = 60000
            failOnError = true
            onSuccess goto "format-post"
            onFailure retry 0 otherwise "failed"
        }

        node("format-post") {
            agent = "formatter"
            prompt = """
                Format the following draft sections into a cohesive blog post.
                Add smooth transitions between sections, fix inconsistencies,
                and add markdown formatting (headers, code blocks, bullet points).

                Draft:
                {combined_draft}
            """.trimIndent()
            writes("final_post")
            onSuccess goto "success"
        }

        end("success")
        end("failed", ExitStatus.FAILURE)
    }
}
