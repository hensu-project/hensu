fun llmEvalSimpleWorkflow() = workflow("llm-eval-simple") {
    description = "Simple LLM evaluation test"
    version = "1.0.0"

    agents {
        agent("writer") {
            role = "Content writer"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.7
        }

        agent("evaluator") {
            role = "Content evaluator"
            model = Models.CLAUDE_SONNET_4_5
            temperature = 0.2
        }
    }

    graph {
        start at "write"

        node("write") {
            agent = "writer"
            prompt = "Write about: {topic}"
            onSuccess goto "evaluate"
        }

        node("evaluate") {
            agent = "evaluator"
            prompt = "Evaluate: {write}"
            outputParams = listOf("score")

            onScore {
                whenScore greaterThanOrEqual 80.0 goto "done"
                whenScore lessThan 80.0 goto "write"
            }
        }

        end("done")
    }
}
