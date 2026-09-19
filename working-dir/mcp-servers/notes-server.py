#!/usr/bin/env python3
"""An example MCP server: a scratch notebook that lives as long as the run does.

It exists to show what a server gives you that a `commands.yaml` entry does not — **state between
calls**. A catalog command is one invocation: it starts, does its work, and exits, so two calls
share nothing. A server is one process that answers many calls, so a note written by the first call
is still there for the second.

Two tools:

  note_add(text)  append a note, answer with how many there are now
  note_list()     return every note written so far, in order

The notes live in memory and are gone when the process is. Hensu starts this server on the first
node that resolves a tool and stops it when the run ends, so "as long as the run does" is the whole
lifetime: nothing here outlives a `hensu run`, and nothing here touches the working directory.

Containment is Hensu's, not this file's. `mcp.yaml` declares `network: false` and the server is
launched inside the same OS sandbox a catalog command gets, with its own private `$HOME` and no
write access to the project. A server that needed to write would have to say so in its `sandbox:`
block, where an operator can read it.

The protocol is line-delimited JSON-RPC over stdin/stdout — `initialize`, `tools/list`,
`tools/call` — which is the whole of what a stdio MCP server has to answer. Written with no
dependencies so it runs anywhere Python does.
"""
import json
import sys

NOTES = []

TOOLS = [
    {
        "name": "note_add",
        "description": "Write a note to the run's scratch notebook. Notes persist for the run.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "The note to write"}},
            "required": ["text"],
        },
    },
    {
        "name": "note_list",
        "description": "List every note written so far in this run, in the order written.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def call(name, arguments):
    if name == "note_add":
        text = str(arguments.get("text", "")).strip()
        if not text:
            return "refused: a note needs text"
        NOTES.append(text)
        return f"noted. the notebook now holds {len(NOTES)} note(s)"
    if name == "note_list":
        if not NOTES:
            return "the notebook is empty"
        return "\n".join(f"{i}. {note}" for i, note in enumerate(NOTES, start=1))
    return f"refused: this server publishes no tool named {name!r}"


def respond(request):
    method = request.get("method")
    ident = request.get("id")
    if ident is None:
        return None  # a notification; nothing to answer
    if method == "initialize":
        result = {
            "protocolVersion": "2024-11-05",
            "serverInfo": {"name": "notes", "version": "1"},
        }
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = request.get("params", {})
        text = call(params.get("name", ""), params.get("arguments", {}))
        result = {"content": [{"type": "text", "text": text}]}
    else:
        return {
            "jsonrpc": "2.0",
            "id": ident,
            "error": {"code": -32601, "message": f"unknown method {method!r}"},
        }
    return {"jsonrpc": "2.0", "id": ident, "result": result}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue  # a half-written line is not worth killing the server over
        reply = respond(request)
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
