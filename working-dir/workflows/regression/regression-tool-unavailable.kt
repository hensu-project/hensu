/**
 * Regression: an agent that declares a tool nobody provides fails on resolution, loudly.
 *
 * The tool loop resolves an agent's declared tool names against the live catalog of the
 * runtime's ToolProviders before it opens a session. A name with no provider behind it is a
 * configuration error, and the node must fail with a message naming what was declared and what
 * was actually available – never proceed as though the tool existed, and never fabricate a
 * result for it.
 *
 * This fixture reaches that path from either runtime. On the CLI, which contributes no
 * ToolProvider yet, the available set is empty. On the server, the available set is whatever the
 * calling tenant's MCP session exposes, so the same run doubles as a check that a genuine catalog
 * is being consulted rather than an empty default.
 *
 * Determinism needs no stub: resolution happens before the first model call, so the outcome does
 * not depend on what an agent decides to do.
 *
 * Run (CLI):
 *   hensu run regression/regression-tool-unavailable -d working-dir -v --no-daemon \
 *     -c '{"task": "summarise the release notes"}'
 *
 * Expect: node `work` fails immediately with
 *   Unresolvable tools for agent 'worker': declared=[wire_transfer], available=[]
 * and the workflow ends at `blocked` (FAILURE). No agent output, no model call.
 */
fun regressionToolUnavailable() = workflow("regression-tool-unavailable") {
    description = "Declared-but-unavailable tool fails at resolution with a declared-vs-available diff"
    version = "1.0.0"

    agents {
        agent("worker") {
            role = "Task executor with tool access"
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            // No runtime provides this name. On the server the tenant's MCP catalog is the
            // available set; on the CLI it is empty until the command and MCP providers land.
            tools = listOf("wire_transfer")
        }
    }

    state {
        input("task", VarType.STRING)
        variable("result", VarType.STRING, "work product – never written, the node fails first")
    }

    graph {
        start at "work"

        node("work") {
            agent = "worker"
            prompt = "Complete this task using your tools: {task}."
            writes("result")

            // The success arm is never taken – resolution fails before the agent runs. The
            // failure arm exists so the outcome is a clean FAILURE end rather than an
            // unroutable node.
            onSuccess goto "done"
            onFailure goto "blocked"
        }

        end("done", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
