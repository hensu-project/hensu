/**
 * Regression: a refused tool call reaches the graph, on a node that also writes its own output.
 *
 * The tool loop records a refusal under `_capability_gap_count`, and the transition reads it. What
 * sits between the two is the post-execution pass that clears every routing variable a node's rules
 * declare and re-extracts it from the agent's JSON. That pass once erased the count, so the run
 * routed as though nothing had been refused.
 *
 * `writes("summary")` is load-bearing. The clear-and-re-extract pass runs only for a node that
 * declares writes or engine variables, so a fixture without it cannot fail the way the engine did.
 * Do not "simplify" it away.
 *
 * `supervised-echo` carries `unattended: false` and nothing else, so an unattended run refuses it
 * on exactly one gate and the refusal sentence is unambiguous. The command itself is `/bin/echo`;
 * granting it adds no capability to the run.
 *
 * The gap arm is declared before the ordinary arm because first match wins. Each end node carries a
 * different exit status, so the status alone reports which arm fired – the same trick
 * `regression-condition-arms` uses.
 *
 * Determinism comes from temperature 0 and a prompt that states the one call to make, so the same
 * input lands on the same behaviour every run.
 *
 * Run (unattended – the refusal path):
 *   hensu run regression/regression-capability-gap -d working-dir -v --no-daemon \
 *     -c '{"subject": "cache invalidation"}'
 * Expect: one DENIED gap for `supervised-echo` in the completion summary, and the run ending at
 * `blocked` (FAILURE).
 *
 * Run (attended – the approval path):
 *   hensu run regression/regression-capability-gap -d working-dir -v --no-daemon --interactive \
 *     -c '{"subject": "cache invalidation"}'
 * Expect: the call to run, no gap recorded, and the run ending at `done` (SUCCESS).
 *
 * An unattended run that reports SUCCESS is the regression: the count was written and then erased
 * before the transition could read it.
 *
 * This file doubles as the false-positive guard for the builder's gap-key check. It routes on
 * `_capability_gap_count` while declaring only `summary`, so `hensu build regression/regression-capability-gap`
 * must succeed.
 */
fun regressionCapabilityGap() = workflow("regression-capability-gap") {
    description = "Capability gap routing – a refused tool call reaches the graph"
    version = "1.0.0"

    agents {
        agent("announcer") {
            role = "Release announcer. You follow the stated procedure literally and report what " +
                "happened, including when a tool refused you."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            // Refused on `unattended: false` alone, so the refusal sentence names one gate.
            tools = listOf("supervised-echo")
        }
    }

    state {
        input("subject", VarType.STRING)
        variable("summary", VarType.STRING, "one-line account of what the agent did")
    }

    graph {
        start at "announce"

        node("announce") {
            agent = "announcer"
            prompt = """
                Announce the subject {subject}.

                Do exactly this, in order:
                  1. Call the supervised-echo tool once, with message set to
                     "announcing {subject}".
                  2. Write one line saying whether the call succeeded or was refused.

                Make no other tool calls.
            """.trimIndent()
            // Declaring an ordinary variable is what makes the clearing pass run over this node.
            // Without it the gap count is never at risk, and the fixture proves nothing.
            writes("summary")

            // First match wins, so the gap arm goes first: a blocked run must not be routed by the
            // success arm just because the agent produced text before it was refused.
            onCondition("_capability_gap_count") {
                whenValue greaterThanOrEqual 1 goto "blocked"
            }
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
