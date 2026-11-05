/**
 * Regression: an agent declaring `model = Models.STUB` is stubbed by name, and only that agent.
 *
 * Stub selection used to be a process-wide switch and nothing else. With `HENSU_STUB_ENABLED`
 * unset, no provider claimed the model `stub`, so agent creation failed before the graph was
 * entered with "No provider found for model: stub". With the switch set, the stub outranked every
 * real provider for every model, so a workflow could not stub one agent and keep another live.
 *
 * The writer declares the stub model; the reviewer declares a live model and is asked for one
 * literal word, so a stubbed reviewer is visible at a glance (its output would start "[STUB]").
 * The ids match no file under `working-dir/stubs/`, so a stubbed agent answers with the "[STUB]"
 * fallback rather than a scripted response.
 *
 * Run with the stub switch unset and an Anthropic key configured:
 *   env -u HENSU_STUB_ENABLED hensu run regression/regression-stub-model -d working-dir -v --no-daemon
 * Expect: SUCCESS; the `stub-write` output starts with "[STUB]"; the `live-review` output is `LIVE`.
 *
 * "No provider supports model 'stub'" at start-up, or a `live-review` output starting "[STUB]",
 * is the regression.
 */
fun regressionStubModel() = workflow("regression-stub-model") {
    description = "Stub model by name – one stubbed agent beside one live agent"
    version = "1.0.0"

    agents {
        agent("stub-writer") {
            role = "Writer"
            model = Models.STUB
        }
        agent("live-reviewer") {
            role = "Reviewer. You follow the stated instruction literally."
            model = Models.CLAUDE_HAIKU_4_5
            temperature = 0.0
        }
    }

    graph {
        start at "stub-write"

        node("stub-write") {
            agent = "stub-writer"
            prompt = "Write one sentence about rain."
            onSuccess goto "live-review"
        }

        node("live-review") {
            agent = "live-reviewer"
            prompt = "Reply with exactly the word LIVE and nothing else."
            onSuccess goto "exit"
        }

        end("exit")
    }
}
