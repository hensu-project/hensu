/**
 * Smoke: a real model calls a tool on a Streamable HTTP MCP server.
 *
 * Declares only `echo`, which both `mcp-servers/http-fixture-server.py` and the reference
 * `@modelcontextprotocol/server-everything` publish, so one workflow drives the loopback exercises
 * of `docs/manual-test-plan-mcp-http.md` against either server and in either protocol era. The
 * server is chosen by the working directory's `mcp.yaml`, never by the workflow.
 *
 * The fixture's `echo` annotates `region` with `x-mcp-header`, so in the modern era the call must
 * carry `Mcp-Param-Region` matching the body, or the fixture answers -32020. A region outside
 * printable ASCII exercises the Base64 sentinel encoding end to end.
 *
 * Run the exercise, not the file: the plan names the `mcp.yaml` and server flags for each case.
 *
 *   hensu run regression/smoke-mcp-http-echo -d <exercise dir> --no-daemon --unattended \
 *     -c '{"message": "ping", "region": "eu-west-1"}'
 *
 * Expect SUCCESS with the echoed text in `summary`. A refused or absent tool routes to `blocked`
 * with a `_capability_gaps` record, which is the expected outcome of exercises H3 and H5.
 */
fun smokeMcpHttpEcho() = workflow("smoke-mcp-http-echo") {
    description = "Smoke – a real model calls echo on a Streamable HTTP MCP server"
    version = "1.0.0"

    agents {
        agent("echoer") {
            role = "Echo operator. You follow the stated procedure literally and report exactly " +
                "what the tool returned, including when it was unavailable or refused."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("echo")
        }
    }

    state {
        input("message", VarType.STRING)
        input("region", VarType.STRING)
        variable("summary", VarType.STRING, "the tool's returned text, verbatim, or why there was none")
    }

    graph {
        start at "echo"

        node("echo") {
            agent = "echoer"
            prompt = """
                Do exactly this, in order:
                  1. Call the echo tool once, with message set to "{message}". If the tool
                     accepts a region parameter, set region to "{region}" exactly as written.
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
