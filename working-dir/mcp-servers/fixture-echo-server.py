#!/usr/bin/env python3
"""Minimal stdio MCP server, used only by the manual test plan.

Answers `initialize`, `tools/list` and `tools/call` over line-delimited JSON-RPC and nothing else.
It exists so an exercise that is about Hensu's launch decision does not also need a third-party
server installed: the question under test is whether a server is started at all, not what it can do.

Not a test fixture of the automated suite, and not an example of how to write an MCP server.
"""
import json
import sys

TOOLS = [
    {
        "name": "fixture_echo",
        "description": "Echo a message back, for launch-decision exercises only",
        "inputSchema": {
            "type": "object",
            "properties": {"message": {"type": "string", "description": "Text to echo"}},
            "required": ["message"],
        },
    }
]


def respond(request):
    method = request.get("method")
    ident = request.get("id")
    if ident is None:
        return None
    if method == "initialize":
        result = {"protocolVersion": "2024-11-05", "serverInfo": {"name": "fixture", "version": "1"}}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        message = request.get("params", {}).get("arguments", {}).get("message", "")
        result = {"content": [{"type": "text", "text": "fixture echoed " + str(message)}]}
    else:
        return {"jsonrpc": "2.0", "id": ident, "error": {"code": -32601, "message": "unknown method"}}
    return {"jsonrpc": "2.0", "id": ident, "result": result}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue
        reply = respond(request)
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
