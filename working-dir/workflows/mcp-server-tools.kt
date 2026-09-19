/**
 * Example: an agent that uses tools published by a locally launched MCP server.
 *
 * `mcp.yaml` declares the server; `mcp-servers/notes-server.py` is the server. Hensu starts it on
 * the first node whose tools have to be resolved, asks it what it publishes, and offers those tools
 * to agents that named them — the workflow never names a process, a port, or a protocol.
 *
 * What a server gives you that `commands.yaml` does not is **state between calls**. A catalog
 * command starts and exits, so two calls share nothing; this server is one process answering both
 * calls, so the note written by the first is still there for the second. That is why the prompt
 * below can ask for a note and then a listing and expect the listing to contain the note.
 *
 * The lifetime is the run's. The server starts lazily, holds its notes in memory, and is stopped
 * when the run ends — nothing here outlives `hensu run`, and nothing here touches the project
 * directory. Containment is decided once, at launch, from the server's own `sandbox:` block rather
 * than per call, because a process that outlives every call it answers cannot be gated by one.
 *
 * Tool names come from the server, not from Hensu. `note_add` and `note_list` are what this server
 * chose to publish; a different server publishes different names, and a name that collides with a
 * catalog command or a built-in file tool is reported rather than silently shadowed.
 *
 * Run:
 *   hensu run mcp-server-tools -d working-dir -v --no-daemon \
 *     -c '{"topic": "cache invalidation"}'
 *
 * Expect two tool calls — an add, then a list — and SUCCESS, with the listing echoing the note.
 * On a host with no working sandbox backend the server is not started at all: its tools are absent,
 * the node fails on the declared-versus-available diff, and the run's **Tool sources** section says
 * why. That is the intended behaviour, not a bug — an uncontained server is never started silently.
 */
fun mcpServerTools() = workflow("mcp-server-tools") {
    description = "Using tools published by a locally launched MCP server"
    version = "1.0.0"

    agents {
        agent("note-taker") {
            role = "Meeting note-taker. You keep notes with the notebook tools you were given, " +
                "and you report what the notebook actually returned."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
            // Published by the `notes` server in mcp.yaml, not by commands.yaml.
            tools = listOf("note_add", "note_list")
        }
    }

    state {
        input("topic", VarType.STRING)
        variable("notebook", VarType.STRING, "what the notebook returned when listed")
    }

    graph {
        start at "take_notes"

        node("take_notes") {
            agent = "note-taker"
            prompt = """
                Record a note about {topic}, then read the notebook back.

                Do exactly this, in order:
                  1. Call note_add with text set to "discussed {topic}".
                  2. Call note_list to read the notebook back.
                  3. Write the notebook's contents, exactly as the tool returned them.

                Make no other tool calls.
            """.trimIndent()
            writes("notebook")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
