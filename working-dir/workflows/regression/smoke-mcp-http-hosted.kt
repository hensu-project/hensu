/**
 * Smoke: a real model calls a tool on a hosted MCP endpoint over TLS.
 *
 * Declares `read_wiki_structure`, published by DeepWiki's official remote endpoint
 * (`https://mcp.deepwiki.com/mcp`, Cognition). Nothing is installed to reach it: Hensu only POSTs.
 * What this adds over the loopback fixture is somebody else's certificate, somebody else's catalog,
 * and a server whose era Hensu has to discover rather than be told.
 *
 * Run the exercise, not the file: see exercise H2 of `docs/manual-test-plan-mcp-http.md`.
 *
 *   hensu run regression/smoke-mcp-http-hosted -d <exercise dir> --no-daemon --unattended \
 *     -c '{"repo": "modelcontextprotocol/servers"}'
 *
 * Expect SUCCESS with a list of wiki topics in `summary`.
 */
fun smokeMcpHttpHosted() = workflow("smoke-mcp-http-hosted") {
    description = "Smoke – a real model calls a hosted MCP endpoint over TLS"
    version = "1.0.0"

    agents {
        agent("reader") {
            role = "Documentation reader. You call the tool you were given and report what it " +
                "returned, including when it was unavailable or refused."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("read_wiki_structure")
        }
    }

    state {
        input("repo", VarType.STRING)
        variable("summary", VarType.STRING, "the first five topics the tool returned, or why there were none")
    }

    graph {
        start at "read"

        node("read") {
            agent = "reader"
            prompt = """
                Do exactly this, in order:
                  1. Call read_wiki_structure once, with repoName set to "{repo}".
                  2. List the first five topics it returned, one per line.

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
