/**
 * Example: Fork-Join with MERGE_MAPS merge strategy
 *
 * This workflow demonstrates:
 * - Parallel data extraction from different sources into distinct keys
 * - MERGE_MAPS spreads branch exports into individual parent state variables
 * - Each branch writes a distinct variable; the join spreads them into parent state as first-class vars
 *
 * Flow:
 *   start → identify → fork(github, docs, community) → join → compile → success
 *
 * Use case: Gather project health metrics from multiple sources in parallel,
 * then spread them into the parent state as individual variables for the report compiler.
 */
fun workflow() = workflow("fork-join-merge-maps") {
    description = "Demonstrates MERGE_MAPS merge – branch exports spread into parent state"

    agents {
        agent("coordinator") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Project assessment coordinator"
        }
        agent("analyst") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Data analyst"
        }
        agent("reporter") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Report compiler"
        }
    }

    state {
        variable("project_name",        VarType.STRING, "name of the project to assess")
        variable("assessment_criteria",  VarType.STRING, "criteria for project health assessment")
        variable("code_metrics",         VarType.STRING, "code quality and activity metrics from repository")
        variable("docs_quality",         VarType.STRING, "documentation coverage and freshness assessment")
        variable("community_health",     VarType.STRING, "community engagement and responsiveness metrics")
        variable("health_report",        VarType.STRING, "final project health report")
    }

    graph {
        start at "identify-project"

        node("identify-project") {
            agent = "coordinator"
            prompt = """
                Prepare an assessment plan for evaluating the health of an open-source project.
                Project: Apache Kafka

                Define the key metrics to gather from:
                1. Code repository (commits, PRs, issues, test coverage)
                2. Documentation (completeness, freshness, examples)
                3. Community (contributors, response times, adoption)
            """.trimIndent()
            writes("project_name", "assessment_criteria")
            onSuccess goto "fork-gather"
        }

        fork("fork-gather") {
            targets("analyze-code", "analyze-docs", "analyze-community")
            onComplete goto "merge-metrics"
        }

        node("analyze-code") {
            agent = "analyst"
            prompt = """
                Analyze the code repository health for: {project_name}

                Assessment criteria: {assessment_criteria}

                Focus on: commit frequency, PR merge time, open issues trend, test coverage estimate.
            """.trimIndent()
            writes("code_metrics")
            onSuccess goto "merge-metrics"
        }

        node("analyze-docs") {
            agent = "analyst"
            prompt = """
                Analyze the documentation quality for: {project_name}

                Assessment criteria: {assessment_criteria}

                Focus on: docs completeness, API coverage, example quality, last major update.
            """.trimIndent()
            writes("docs_quality")
            onSuccess goto "merge-metrics"
        }

        node("analyze-community") {
            agent = "analyst"
            prompt = """
                Analyze the community health for: {project_name}

                Assessment criteria: {assessment_criteria}

                Focus on: active contributors, average issue response time, adoption trend, ecosystem plugins.
            """.trimIndent()
            writes("community_health")
            onSuccess goto "merge-metrics"
        }

        join("merge-metrics") {
            await("fork-gather")
            mergeStrategy = MergeStrategy.MERGE_MAPS
            exports("code_metrics", "docs_quality", "community_health")
            writes("code_metrics", "docs_quality", "community_health")
            timeout = 60000
            failOnError = true
            onSuccess goto "compile-report"
            onFailure retry 0 otherwise "failed"
        }

        node("compile-report") {
            agent = "reporter"
            prompt = """
                Compile a project health report from the following metrics:

                Code metrics: {code_metrics}
                Documentation quality: {docs_quality}
                Community health: {community_health}

                Structure the report as:
                1. Executive summary (overall health score)
                2. Code health details
                3. Documentation assessment
                4. Community vitality
                5. Recommendations for improvement
            """.trimIndent()
            writes("health_report")
            onSuccess goto "success"
        }

        end("success")
        end("failed", ExitStatus.FAILURE)
    }
}
