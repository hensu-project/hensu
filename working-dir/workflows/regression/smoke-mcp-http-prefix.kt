/**
 * Smoke: one hosted endpoint declared twice stays usable under a prefix.
 *
 * Declares `read_wiki_structure` and `alt_read_wiki_structure`. The second name exists only when the
 * working directory's `mcp.yaml` declares the same endpoint a second time with `prefix: "alt_"`, and
 * the prefix must be stripped before the call reaches the server — the server has never heard of
 * `alt_read_wiki_structure`, so a prefix that leaks comes back as an unknown-tool error.
 *
 * Run the exercise, not the file: see exercise H4 of `docs/manual-test-plan-mcp-http.md`.
 *
 *   hensu run regression/smoke-mcp-http-prefix -d <exercise dir> --no-daemon --unattended \
 *     -c '{"repo": "modelcontextprotocol/servers"}'
 *
 * Expect SUCCESS and two tool calls, one per name, both answered.
 */
fun smokeMcpHttpPrefix() = workflow("smoke-mcp-http-prefix") {
    description = "Smoke – two declarations of one endpoint, kept apart by a prefix"
    version = "1.0.0"

    agents {
        agent("reader") {
            role = "Documentation reader. You call the tools you were given and report what each " +
                "returned, including when one was unavailable or refused."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            tools = listOf("read_wiki_structure", "alt_read_wiki_structure")
        }
    }

    state {
        input("repo", VarType.STRING)
        variable("summary", VarType.STRING, "for each tool, whether it answered and its first topic")
    }

    graph {
        start at "read"

        node("read") {
            agent = "reader"
            prompt = """
                Do exactly this, in order:
                  1. Call read_wiki_structure once, with repoName set to "{repo}".
                  2. Call alt_read_wiki_structure once, with repoName set to "{repo}".
                  3. For each of the two tools, write one line: the tool name, whether it
                     answered, and the first topic it returned.

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
