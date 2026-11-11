/**
 * Smoke: a real model calls a tool on a local stdio MCP server.
 *
 * Declares only `fixture_echo`, which `mcp-servers/fixture-echo-server.py` publishes, so the stdio
 * exercises of `docs/manual-test-plan-mcp-relay-pr1.md` share one workflow: the `tools:` filter
 * applied to a launched server, and a call that outlives its `timeout:` being cancelled with
 * `notifications/cancelled`. Which server answers, and how, is chosen by the working directory's
 * `mcp.yaml` and the fixture's flags, never by the workflow.
 *
 * Run the exercise, not the file: the plan names the `mcp.yaml` and fixture flags for each case.
 *
 *   hensu run regression/smoke-mcp-stdio-echo -d <exercise dir> --no-daemon --unattended \
 *     -c '{"message": "ping"}'
 *
 * Expect SUCCESS with the echoed text in `summary`. A filtered-out tool routes to `blocked`.
 */
fun smokeMcpStdioEcho() = workflow("smoke-mcp-stdio-echo") {
    description = "Smoke – a real model calls fixture_echo on a stdio MCP server"
    version = "1.0.0"

    agents {
        agent("echoer") {
            role = "Echo operator. You follow the stated procedure literally and report exactly " +
                "what the tool returned, including when it was unavailable, failed or refused."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("fixture_echo")
        }
    }

    state {
        input("message", VarType.STRING)
        variable("summary", VarType.STRING, "the tool's returned text, verbatim, or why there was none")
    }

    graph {
        start at "echo"

        node("echo") {
            agent = "echoer"
            prompt = """
                The tool's reply is not your message repeated: it is text only the tool knows,
                so you cannot report it without making the call.

                Do exactly this, in order:
                  1. Call the fixture_echo tool once, with message set to "{message}".
                  2. Report the text the tool returned, verbatim. If the call failed, report the
                     failure message verbatim instead.

                Make no other tool calls, and do not retry a failed call.
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
