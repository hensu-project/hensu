/**
 * Example: an agent that uses catalog commands as tools.
 *
 * The agent never writes a command line. It picks a tool by name and supplies parameters, and the
 * catalog decides what actually runs — `commands.yaml` binds `count-lines` to `/usr/bin/wc -l`
 * with the path bound as one whole argv token, so nothing the model produces can become a second
 * command or a shell fragment. Read that file beside this one: the pair is the whole story of what
 * a local run may do.
 *
 * `tools = listOf(...)` is a second, narrower grant. The catalog says what this *deployment* may
 * run; the node says what *this agent* may reach. An agent asking for anything outside its list is
 * refused and the refusal is recorded, so a node gets the smallest set that does its job.
 *
 * Both tools here are ungated — no `approval: required`, no `unattended: false` — so the example
 * runs end to end with nobody watching. `commands.yaml` also carries gated entries
 * (`gated-echo`, `supervised-echo`); swap one in and run again to watch an unattended run refuse it
 * and record a capability gap instead of prompting an absent human.
 *
 * The node routes a refusal as well as a success. `_capability_gap_count` is written by the engine
 * only when something is actually refused, so on a healthy run the gap arm simply does not match
 * and says nothing — routing on it costs a clean run nothing.
 *
 * Run:
 *   hensu run catalog-commands -d working-dir -v --no-daemon \
 *     -c '{"file": "commands.yaml"}'
 *
 * Expect two tool calls — a count, then a report — and SUCCESS. Add `--verbose` to see the argv
 * each call resolved to, which is what a reviewer would be shown if a tool required approval.
 */
fun catalogCommands() = workflow("catalog-commands") {
    description = "Using commands.yaml entries as agent tools"
    version = "1.0.0"

    agents {
        agent("inspector") {
            role = "Repository inspector. You answer questions about files by measuring them with " +
                "the tools you were given, never by guessing."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            // The node's grant, narrower than the catalog's.
            tools = listOf("count-lines", "echo-result")
        }
    }

    state {
        input("file", VarType.STRING)
        variable("report", VarType.STRING, "one line stating the file and its line count")
    }

    graph {
        start at "inspect"

        node("inspect") {
            agent = "inspector"
            prompt = """
                Report how large the file {file} is.

                Do exactly this, in order:
                  1. Call count-lines with path set to "{file}".
                  2. Call echo-result with message set to "{file} has N lines", replacing N with
                     the number the first call returned.
                  3. Write that same sentence as your report.

                Make no other tool calls, and do not guess the number — it comes from the tool.
            """.trimIndent()
            writes("report")

            // A refused tool leaves a record the engine writes, and an ordinary condition routes on
            // it. Swap a gated entry (gated-echo, supervised-echo) into the tools list above and
            // rerun unattended to watch this arm fire instead of onSuccess.
            onCondition("_capability_gap_count") {
                whenValue greaterThanOrEqual 1 goto "blocked"
            }
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
