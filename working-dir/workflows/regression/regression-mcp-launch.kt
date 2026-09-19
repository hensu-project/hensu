/**
 * Regression: a run with no human cannot borrow another run's reviewer.
 *
 * Starting an MCP server with no containment available is the one approval decision that belongs to
 * no call – the process outlives every call it answers, so it is made once, before any call context
 * exists. The gate therefore answers it from the set of runs currently in flight rather than from a
 * call, and it used to answer from a single field holding whichever run wrote it last. A daemon
 * serving an attended run beside an unattended one would then send the unattended run's escalation
 * to the attended run's human.
 *
 * The agent declares only the fixture MCP server's tool, so tool resolution for this node is what
 * forces the launch decision. `working-dir/mcp.yaml` declares that server with `unattended: false`.
 *
 * Run the exercise, not the file: this fixture is meaningful only beside a second, attended run
 * parked at a review, and only on a daemon with no working containment backend. See Track Z of
 * `docs/manual-test-plan-cli-tool-exec-pr5.md`.
 *
 * Expect: the server is not started, `fixture_echo` is absent from the catalog, and the run records
 * an UNKNOWN_TOOL capability gap. The attended run beside it is never prompted.
 */
fun regressionMcpLaunch() = workflow("regression-mcp-launch") {
    description = "MCP launch approval – an unattended run does not borrow another run's reviewer"
    version = "1.0.0"

    agents {
        agent("echoer") {
            role = "Echo operator. You follow the stated procedure literally and report what " +
                "happened, including when a tool was not available to you."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("fixture_echo")
        }
    }

    state {
        input("phrase", VarType.STRING)
        variable("summary", VarType.STRING, "one-line account of what the agent did")
    }

    graph {
        start at "echo"

        node("echo") {
            agent = "echoer"
            prompt = """
                Echo the phrase {phrase}.

                Do exactly this, in order:
                  1. Call the fixture_echo tool once, with message set to "{phrase}".
                  2. Write one line saying whether the call succeeded or the tool was unavailable.

                Make no other tool calls.
            """.trimIndent()
            writes("summary")

            onCondition("_capability_gap_count") {
                whenValue greaterThanOrEqual 1 goto "blocked"
            }
            onSuccess goto "done"
            // A refused launch leaves the tool absent, and a node whose declared tools cannot be
            // resolved fails before it calls anything — so the blocked arm has to catch a failure
            // as well as a recorded gap.
            onFailure goto "blocked"
        }

        end("done", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
