/**
 * Example: Fork-Join parallel execution pattern
 *
 * This workflow demonstrates:
 * - Fork node spawning multiple parallel execution paths
 * - Each forked path executes independently using virtual threads
 * - Join node awaiting all forked executions
 * - Merging results with configurable strategy
 * - Using merged output in subsequent nodes
 *
 * Flow:
 *   start → analyze-task → fork(research-a, research-b, research-c) → join → synthesize → success
 *
 *   Sub-flow 1 is a multi-node chain (research → refine) to validate
 *   that executeUntil() traverses full sub-flows, not just single nodes.
 */
fun workflow() = workflow("fork-join-demo") {
    description = "Demonstrates fork-join parallel execution with virtual threads"

    agents {
        agent("analyzer") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Task analyzer"
        }
        agent("researcher") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Research specialist"
        }
        agent("synthesizer") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Content synthesizer"
        }
    }

    state {
        variable("analysis",           VarType.STRING, "identified research areas from task analysis")
        variable("area_1",             VarType.STRING, "first research area identified by the analyzer")
        variable("area_2",             VarType.STRING, "second research area identified by the analyzer")
        variable("area_3",             VarType.STRING, "third research area identified by the analyzer")
        variable("research_1_raw",     VarType.STRING, "raw research findings for the first area")
        variable("research_1",         VarType.STRING, "refined research findings for the first area")
        variable("research_2",         VarType.STRING, "research findings for the second area")
        variable("research_3",         VarType.STRING, "research findings for the third area")
        variable("research_results",   VarType.STRING, "merged research results from all branches")
        variable("report",             VarType.STRING, "final synthesized report")
    }

    graph {
        start at "analyze-task"

        // Initial analysis node
        node("analyze-task") {
            agent = "analyzer"
            prompt = "Analyze the following topic and identify exactly 3 key research areas: AI workflow automation"
            writes("analysis", "area_1", "area_2", "area_3")
            onSuccess goto "parallel-research"
        }

        // Fork: spawn 3 parallel research tasks
        fork("parallel-research") {
            targets("research-area-1", "research-area-2", "research-area-3")
            onComplete goto "merge-research"
        }

        // Sub-flow 1: multi-node chain (research → refine → join boundary)
        node("research-area-1") {
            agent = "researcher"
            prompt = """
                Research the following area in depth: {area_1}
                Provide detailed findings with concrete examples and patterns.
            """.trimIndent()
            writes("research_1_raw")
            onSuccess goto "refine-area-1"
        }

        node("refine-area-1") {
            agent = "researcher"
            prompt = """
                Review and refine the following raw research findings for clarity and accuracy.
                Remove redundancies, strengthen examples, and ensure logical flow.

                Raw findings:
                {research_1_raw}
            """.trimIndent()
            writes("research_1")
            onSuccess goto "merge-research"
        }

        // Sub-flow 2: transitions to join boundary
        node("research-area-2") {
            agent = "researcher"
            prompt = """
                Research the following area in depth: {area_2}
                Provide detailed findings with concrete examples and patterns.
            """.trimIndent()
            writes("research_2")
            onSuccess goto "merge-research"
        }

        // Sub-flow 3: transitions to join boundary
        node("research-area-3") {
            agent = "researcher"
            prompt = """
                Research the following area in depth: {area_3}
                Provide detailed findings with concrete examples and patterns.
            """.trimIndent()
            writes("research_3")
            onSuccess goto "merge-research"
        }

        // Join: await all research branches and merge results
        join("merge-research") {
            await("parallel-research")
            mergeStrategy = MergeStrategy.COLLECT_ALL
            exports("research_1", "research_2", "research_3")
            writes("research_results")
            timeout = 60000  // 60 second timeout
            failOnError = true
            onSuccess goto "synthesize-report"
            onFailure retry 0 otherwise "research-failed"
        }

        // Synthesize final report from merged research
        node("synthesize-report") {
            agent = "synthesizer"
            prompt = """
                Synthesize a comprehensive report from the following research results:

                {research_results}

                Create a well-structured summary covering:
                1. Key technical findings
                2. Practical applications
                3. Future outlook
            """.trimIndent()
            writes("report")
            onSuccess goto "success"
        }

        // End nodes
        end("success")
        end("research-failed", ExitStatus.FAILURE)
    }
}
