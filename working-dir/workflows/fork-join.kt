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
 */
fun workflow() = workflow("fork-join-demo") {
    description = "Demonstrates fork-join parallel execution with virtual threads"

    agents {
        agent("analyzer") {
            model = "stub"
            role = "Task analyzer"
        }
        agent("researcher") {
            model = "stub"
            role = "Research specialist"
        }
        agent("synthesizer") {
            model = "stub"
            role = "Content synthesizer"
        }
    }

    state {
        variable("analysis",           VarType.STRING, "identified research areas from task analysis")
        variable("research_technical", VarType.STRING, "findings on technical aspects of AI workflow automation")
        variable("research_use_cases", VarType.STRING, "findings on practical use cases for AI workflow automation")
        variable("research_trends",    VarType.STRING, "findings on future trends in AI workflow automation")
        variable("research_results",   VarType.STRING, "merged research results from all branches")
        variable("report",             VarType.STRING, "final synthesized report")
    }

    graph {
        start at "analyze-task"

        // Initial analysis node
        node("analyze-task") {
            agent = "analyzer"
            prompt = "Analyze the following topic and identify 3 key research areas: AI workflow automation"
            writes("analysis")
            onSuccess goto "parallel-research"
        }

        // Fork: spawn 3 parallel research tasks
        fork("parallel-research") {
            targets("research-area-1", "research-area-2", "research-area-3")
            onComplete goto "merge-research"
        }

        // Research branch 1: Technical aspects
        node("research-area-1") {
            agent = "researcher"
            prompt = """
                Research technical aspects of AI workflow automation.
                Focus on: architecture patterns, execution engines, state management.
                Previous analysis: {analysis}
            """.trimIndent()
            writes("research_technical")
        }

        // Research branch 2: Use cases
        node("research-area-2") {
            agent = "researcher"
            prompt = """
                Research practical use cases for AI workflow automation.
                Focus on: enterprise applications, developer tools, automation scenarios.
                Previous analysis: {analysis}
            """.trimIndent()
            writes("research_use_cases")
        }

        // Research branch 3: Future trends
        node("research-area-3") {
            agent = "researcher"
            prompt = """
                Research future trends in AI workflow automation.
                Focus on: emerging patterns, potential innovations, market direction.
                Previous analysis: {analysis}
            """.trimIndent()
            writes("research_trends")
        }

        // Join: await all research branches and merge results
        join("merge-research") {
            await("parallel-research")
            mergeStrategy = MergeStrategy.COLLECT_ALL
            outputField = "research_results"
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
