/**
 * Regression: every settled tool call gets its own audit row, carrying its own arguments.
 *
 * A row is assembled from two events – the arguments arrive with the request, the outcome with the
 * result – and the sink has to hold the request until the result lands. It once held it under the
 * node id and the tool name. Those identify a call only while no two concurrent calls share both,
 * which is true of today's graph shapes and is a property of the graph rather than of the audit;
 * the loop now mints a call id and both sinks pair on that.
 *
 * Two calls to one tool from one node is the shape that makes the pairing observable at all. Run
 * sequentially, as here, the old keying also produced correct rows – so this fixture is not a
 * reproduction of a live defect. It pins the property the trail is read for: two calls, two rows,
 * each row's arguments belonging to the call its outcome describes.
 *
 * `send-notification` is deliberately an unrestricted entry – no `unattended: false`, no
 * `approval: required` – so an unattended run settles both calls with status SUCCESS and the
 * exercise reads the pairing rather than the gate.
 *
 * The two messages are distinct and stated literally in the prompt, so a row carrying the wrong
 * arguments is visible without knowing which call ran first.
 *
 * Run:
 *   hensu run regression/regression-audit-pairing -d working-dir -v --no-daemon \
 *     -c '{"release": "4.2"}'
 * Expect: SUCCESS, and the last two lines of `~/.hensu/tool-audit.log` carrying two distinct
 * `call_id` values, one with `arguments.message` ending "first" and one ending "second".
 *
 * Two rows with identical arguments, or a row whose `arguments` is empty, is the regression.
 */
fun regressionAuditPairing() = workflow("regression-audit-pairing") {
    description = "Audit pairing – two calls of one tool keep their own arguments"
    version = "1.0.0"

    agents {
        agent("notifier") {
            role = "Release notifier. You follow the stated procedure literally, making exactly " +
                "the calls you are told to make and no others."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("send-notification")
        }
    }

    state {
        input("release", VarType.STRING)
        variable("summary", VarType.STRING, "one-line account of the two notifications sent")
    }

    graph {
        start at "notify"

        node("notify") {
            agent = "notifier"
            prompt = """
                Notify the operator about release {release}.

                Do exactly this, in order:
                  1. Call send-notification once with message set to
                     "release {release} first".
                  2. Call send-notification a second time with message set to
                     "release {release} second".
                  3. Write one line saying that both notifications were sent.

                Make no other tool calls.
            """.trimIndent()
            writes("summary")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
