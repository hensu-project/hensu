#!/usr/bin/env python3
"""Minimal stdio MCP server, used only by the manual test plan.

Answers `initialize`, `tools/list` and `tools/call` over line-delimited JSON-RPC and nothing else.
It exists so an exercise that is about Hensu's launch decision does not also need a third-party
server installed: the question under test is whether a server is started at all, not what it can do.

Not a test fixture of the automated suite, and not an example of how to write an MCP server.

    python3 fixture-echo-server.py [--log FILE] [--slow-ms N]

--log FILE     Append one JSON line per message received: its method, its id, and for
               notifications/cancelled the requestId it cancels. The file has to lie inside a
               path the server's `sandbox: write:` grants.
--slow-ms N    Answer each tools/call N ms after it arrived, on a worker thread, so a
               notifications/cancelled can arrive meanwhile. A cancelled call is never answered.
"""
import argparse
import json
import sys
import threading
import time

OUT = threading.Lock()
CANCELLED = set()

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
        result = {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "fixture", "version": "1"},
        }
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        message = request.get("params", {}).get("arguments", {}).get("message", "")
        result = {"content": [{"type": "text", "text": "fixture echoed " + str(message)}]}
    else:
        return {"jsonrpc": "2.0", "id": ident, "error": {"code": -32601, "message": "unknown method"}}
    return {"jsonrpc": "2.0", "id": ident, "result": result}


def write(reply):
    with OUT:
        sys.stdout.write(json.dumps(reply) + "\n")
        sys.stdout.flush()


def log(options, entry):
    if options.log:
        with OUT, open(options.log, "a", encoding="utf-8") as out:
            out.write(json.dumps(entry) + "\n")


def answer_later(options, request):
    time.sleep(options.slow_ms / 1000.0)
    with OUT:
        cancelled = request.get("id") in CANCELLED
    if cancelled:
        log(options, {"answered": False, "id": request.get("id")})
        return
    write(respond(request))
    log(options, {"answered": True, "id": request.get("id")})


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--log")
    parser.add_argument("--slow-ms", type=int, default=0)
    options = parser.parse_args()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue
        entry = {"rpc": request.get("method"), "id": request.get("id")}
        if request.get("method") == "notifications/cancelled":
            params = request.get("params") or {}
            entry["requestId"] = params.get("requestId")
            entry["reason"] = params.get("reason")
            with OUT:
                CANCELLED.add(params.get("requestId"))
        log(options, entry)
        if request.get("method") == "tools/call" and options.slow_ms:
            threading.Thread(target=answer_later, args=(options, request), daemon=True).start()
            continue
        reply = respond(request)
        if reply is not None:
            write(reply)


if __name__ == "__main__":
    main()
