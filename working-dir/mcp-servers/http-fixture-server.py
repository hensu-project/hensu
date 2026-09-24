#!/usr/bin/env python3
"""A loopback MCP endpoint speaking Streamable HTTP, for the manual test plan.

This is not an example of how to write an MCP server. It is a witness: it validates what a real
server of the chosen revision would validate, and it writes down what every request carried, so an
exercise can assert on what actually left the client rather than on what the client meant to send.

Standard library only — nothing is installed to run it.

    python3 http-fixture-server.py --port 8931 [--era modern|legacy] [--log FILE]
        [--require-bearer VALUE] [--redirect-to URL] [--publish-invalid] [--publish-nullable]

--era modern   Revision 2026-07-28. No handshake, no session. Every POST must carry
               MCP-Protocol-Version and Mcp-Method; a tools/call must carry Mcp-Name and one
               Mcp-Param-{Name} header per annotated argument, each matching the body after
               Base64-sentinel decoding. Any disagreement is 400 with JSON-RPC -32020.
--era legacy   Revision 2024-11-05. `initialize` first, a minted Mcp-Session-Id, and a request
               carrying the modern protocol header answered the way a server predating it would:
               a 404 that is not JSON-RPC.
--require-bearer VALUE
               Answer 401 to any request whose Authorization header is not exactly
               "Bearer VALUE". The log records only whether it matched — never the value.
--redirect-to URL
               Answer every tools/call with 302 and this Location. A relative URL stays on this
               endpoint; the /moved path is served exactly like /mcp.
--publish-invalid
               Also publish `measure`, whose only parameter is a `number` annotated with
               x-mcp-header. A modern client must leave that tool out of its catalog.
--publish-nullable
               Also publish `repeat`, whose `times` is typed ["integer", "null"]. A call whose
               `times` is neither an integer nor null is answered as a tool error, so a client
               that offered the model a string instead of a nullable integer shows up as a
               failed call rather than a quietly coerced one.

The log is JSON lines, one per request: path, HTTP method, JSON-RPC method, whether the bearer
matched, Mcp-Name, every Mcp-Param-* header as received, the JSON type of every argument of a
tools/call, and the status answered.
"""

import argparse
import base64
import itertools
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODERN = "2026-07-28"
LEGACY = "2024-11-05"
HEADER_MISMATCH = -32020

ECHO = {
    "name": "echo",
    "description": "Echoes a message back. The optional region is mirrored into a header.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "message": {"type": "string", "description": "Text to echo back"},
            "region": {
                "type": "string",
                "description": "Region the echo is routed to",
                "x-mcp-header": "Region",
            },
        },
        "required": ["message"],
    },
}

MEASURE = {
    "name": "measure",
    "description": "Invalid on purpose: a number cannot be mirrored into a header.",
    "inputSchema": {
        "type": "object",
        "properties": {"ratio": {"type": "number", "x-mcp-header": "Ratio"}},
    },
}

REPEAT = {
    "name": "repeat",
    "description": "Repeats a message. times is optional; null or absent means once.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "message": {"type": "string", "description": "Text to repeat"},
            "times": {"type": ["integer", "null"], "description": "How many times to repeat it"},
        },
        "required": ["message"],
    },
}


def decode(value):
    if value is not None and value.startswith("=?base64?") and value.endswith("?="):
        return base64.b64decode(value[len("=?base64?"):-2]).decode("utf-8")
    return value


def annotations(schema, path=()):
    """Yields (header name, property path) for every annotation reached through properties."""
    for name, child in (schema.get("properties") or {}).items():
        if isinstance(child, dict):
            if isinstance(child.get("x-mcp-header"), str):
                yield child["x-mcp-header"], path + (name,)
            yield from annotations(child, path + (name,))


class Fixture:
    def __init__(self, options):
        self.options = options
        self.lock = threading.Lock()
        self.sessions = itertools.count(1)
        self.tools = ([ECHO] + ([MEASURE] if options.publish_invalid else [])
                      + ([REPEAT] if options.publish_nullable else []))

    def log(self, entry):
        line = json.dumps(entry, ensure_ascii=False)
        with self.lock:
            if self.options.log:
                with open(self.options.log, "a", encoding="utf-8") as out:
                    out.write(line + "\n")
            print(line, file=sys.stderr, flush=True)


class Handler(BaseHTTPRequestHandler):
    fixture = None
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # the JSON log replaces the default access log
        pass

    # ------------------------------------------------------------------ plumbing

    def answer(self, status, body=None, content_type="application/json", headers=None):
        payload = b"" if body is None else (
            body if isinstance(body, bytes) else json.dumps(body).encode("utf-8"))
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        if payload:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)
        self.entry["status"] = status

    def error(self, status, rpc_id, code, message, data=None):
        err = {"code": code, "message": message}
        if data is not None:
            err["data"] = data
        self.answer(status, {"jsonrpc": "2.0", "id": rpc_id, "error": err})

    def result(self, rpc_id, result, headers=None):
        if self.modern:
            result = dict(result, resultType="complete",
                          _meta={"io.modelcontextprotocol/serverInfo": {"name": "hensu-fixture"}})
        self.answer(200, {"jsonrpc": "2.0", "id": rpc_id, "result": result}, headers=headers)

    # ------------------------------------------------------------------ dispatch

    def do_GET(self):
        self.entry = {"path": self.path, "http": "GET"}
        self.answer(405, b"Method Not Allowed", "text/plain")
        self.fixture.log(self.entry)

    def do_DELETE(self):
        self.entry = {"path": self.path, "http": "DELETE"}
        self.answer(405 if self.fixture.options.era == "modern" else 200)
        self.fixture.log(self.entry)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8")
        try:
            message = json.loads(raw)
        except ValueError:
            message = {}
        options = self.fixture.options
        rpc = message.get("method", "")
        rpc_id = message.get("id")
        params = message.get("params") or {}

        auth = self.headers.get("Authorization")
        self.entry = {
            "path": self.path,
            "http": "POST",
            "rpc": rpc,
            "auth": "none" if auth is None else (
                "match" if options.require_bearer and auth == "Bearer " + options.require_bearer
                else "present"),
            "mcp-name": self.headers.get("Mcp-Name"),
            "params": {k: v for k, v in self.headers.items() if k.lower().startswith("mcp-param-")},
        }
        try:
            self.dispatch(rpc, rpc_id, params)
        finally:
            self.fixture.log(self.entry)

    def dispatch(self, rpc, rpc_id, params):
        options = self.fixture.options
        self.modern = options.era == "modern"

        if self.path not in ("/mcp", "/moved"):
            self.answer(404, b"Not Found", "text/plain")
            return
        if options.require_bearer and self.entry["auth"] != "match":
            self.answer(401, b"Unauthorized", "text/plain",
                        {"WWW-Authenticate": 'Bearer realm="fixture"'})
            return

        if rpc.startswith("notifications/"):
            self.answer(202)
            return

        announced = self.headers.get("MCP-Protocol-Version")
        if not self.modern and announced == MODERN:
            # A server predating the header answers with whatever its router does for an
            # unknown shape, which is not JSON-RPC.
            self.answer(404, b"<html>Not Found</html>", "text/html")
            return
        if self.modern:
            if announced != MODERN:
                self.error(400, rpc_id, -32022, "unsupported protocol version",
                           {"supported": [MODERN]})
                return
            if self.headers.get("Mcp-Method") != rpc:
                self.error(400, rpc_id, HEADER_MISMATCH, "Header mismatch: Mcp-Method")
                return

        if rpc == "initialize" and not self.modern:
            session = "fixture-session-%d" % next(self.fixture.sessions)
            self.result(rpc_id, {
                "protocolVersion": LEGACY,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "hensu-fixture", "version": "1"},
            }, headers={"Mcp-Session-Id": session})
            return
        if rpc == "tools/list":
            self.result(rpc_id, {"tools": self.fixture.tools})
            return
        if rpc == "tools/call":
            self.call(rpc_id, params)
            return
        self.error(404 if self.modern else 400, rpc_id, -32601, "Method not found: " + rpc)

    def call(self, rpc_id, params):
        options = self.fixture.options
        name = params.get("name")
        arguments = params.get("arguments") or {}
        self.entry["argument-types"] = {k: json_type(v) for k, v in arguments.items()}

        if options.redirect_to and self.path == "/mcp":
            self.answer(302, b"", "text/plain", {"Location": options.redirect_to})
            return

        tool = next((t for t in self.fixture.tools if t["name"] == name), None)
        if tool is None:
            self.error(400 if self.modern else 200, rpc_id, -32602, "Unknown tool: %s" % name)
            return

        if self.modern:
            carried = decode(self.headers.get("Mcp-Name"))
            if carried != name:
                self.error(400, rpc_id, HEADER_MISMATCH,
                           "Header mismatch: Mcp-Name %r does not match body %r" % (carried, name))
                return
            for header, path in annotations(tool["inputSchema"]):
                value = arguments
                for step in path:
                    value = value.get(step) if isinstance(value, dict) else None
                sent = decode(self.headers.get("Mcp-Param-" + header))
                if value is None and sent is not None:
                    self.error(400, rpc_id, HEADER_MISMATCH,
                               "Header mismatch: Mcp-Param-%s sent for an absent argument" % header)
                    return
                if value is not None:
                    expected = str(value).lower() if isinstance(value, bool) else str(value)
                    if sent != expected:
                        self.error(400, rpc_id, HEADER_MISMATCH,
                                   "Header mismatch: Mcp-Param-%s %r does not match body %r"
                                   % (header, sent, expected))
                        return

        if name == "repeat":
            times = arguments.get("times")
            if times is not None and (isinstance(times, bool) or not isinstance(times, int)):
                self.result(rpc_id, {"isError": True, "content": [{"type": "text", "text":
                    "times must be an integer or null, got %s" % json_type(times)}]})
                return
            text = " ".join([arguments.get("message", "")] * (1 if times is None else times))
            self.result(rpc_id, {"content": [{"type": "text", "text": text}]})
            return

        text = "echo: %s" % arguments.get("message", "")
        if arguments.get("region") is not None:
            text += " (region %s)" % arguments["region"]
        self.result(rpc_id, {"content": [{"type": "text", "text": text}]})


def json_type(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    return "array" if isinstance(value, list) else "object"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--era", choices=("modern", "legacy"), default="modern")
    parser.add_argument("--log")
    parser.add_argument("--require-bearer")
    parser.add_argument("--redirect-to")
    parser.add_argument("--publish-invalid", action="store_true")
    parser.add_argument("--publish-nullable", action="store_true")
    options = parser.parse_args()

    Handler.fixture = Fixture(options)
    server = ThreadingHTTPServer(("127.0.0.1", options.port), Handler)
    print("fixture listening on http://127.0.0.1:%d/mcp (%s era)" % (options.port, options.era),
          file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
