/**
 * Smoke: a real model is told a nullable integer is an integer.
 *
 * JSON Schema writes "nullable integer" as `"type": ["integer", "null"]`. The witness
 * `mcp-servers/http-fixture-server.py --publish-nullable` publishes `repeat`, whose `times` is typed
 * that way, and answers a call whose `times` is a string as a tool error. A client that flattens the
 * list-valued type to a string invites the model to send `"3"`; one that reads it as an integer gets
 * `3`. The witness log records the JSON type of every argument, so the exercise asserts on what left
 * the client rather than on what the model reported.
 *
 *   hensu run regression/smoke-mcp-http-nullable -d <exercise dir> --no-daemon --unattended \
 *     -c '{"message": "ping", "times": "3"}'
 *
 * Expect SUCCESS with "ping ping ping" in `summary`, and `"times": "integer"` in the witness log.
 */
fun smokeMcpHttpNullable() = workflow("smoke-mcp-http-nullable") {
    description = "Smoke – a real model sends a nullable integer as an integer"
    version = "1.0.0"

    agents {
        agent("repeater") {
            role = "Repeat operator. You follow the stated procedure literally and report exactly " +
                "what the tool returned, including when it was unavailable, refused or failed."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("repeat")
        }
    }

    state {
        input("message", VarType.STRING)
        input("times", VarType.STRING)
        variable("summary", VarType.STRING, "the tool's returned text, verbatim, or why there was none")
    }

    graph {
        start at "repeat"

        node("repeat") {
            agent = "repeater"
            prompt = """
                Do exactly this, in order:
                  1. Call the repeat tool once, with message set to "{message}" and times set
                     to {times}.
                  2. Report the text the tool returned, verbatim.

                Make no other tool calls.
            """.trimIndent()
            writes("summary")

            onCondition("_capability_gap_count") {
                whenValue greaterThanOrEqual 1 goto "blocked"
            }
            onSuccess goto "done"
            onFailure goto "blocked"
        }

        end("done", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
