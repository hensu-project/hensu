package io.hensu.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/// A minimal MCP server, launched as a real process by the stdio client tests.
///
/// Mocking the client's own transport would prove nothing: the behaviour worth
/// testing is a process on the other end of two pipes, with the line framing,
/// the correlation ids and the kill-on-close all real. This fixture is small
/// enough to read in one sitting and needs no network and no container.
///
/// Arguments select a behaviour:
/// - no arguments – answer everything
/// - `--swallow <method>` – read that method's request and never answer it, so
///   the client's per-request deadline is the only thing that can end the call
/// - `--noisy` – write a line to standard error before answering, proving the
///   client drains it instead of deadlocking on a full pipe
/// - `--exit-on <method>` – exit without answering that method, so a caller can
///   exercise a server that dies in the middle of a run
/// - `--deaf-after <method>` – answer that method, then stay alive without ever
///   reading standard input again, so the pipe to the server fills and the
///   client's send blocks instead of its reply
///
/// Calling the tool named `env` reports whether one variable reached the child,
/// which is how the environment-isolation test reads the launched process.
///
/// @implNote **Not part of the published module.** Test fixture only.
public final class FakeMcpServer {

    private FakeMcpServer() {
        // Entry point only
    }

    /// Runs the fixture until its standard input closes.
    ///
    /// @param args behaviour selectors, not null
    /// @throws IOException if the pipes fail, which fails the test that owns it
    static void main(String[] args) throws IOException {
        String swallow = null;
        String exitOn = null;
        String deafAfter = null;
        boolean noisy = false;
        for (int i = 0; i < args.length; i++) {
            if ("--swallow".equals(args[i]) && i + 1 < args.length) {
                swallow = args[++i];
            } else if ("--exit-on".equals(args[i]) && i + 1 < args.length) {
                exitOn = args[++i];
            } else if ("--deaf-after".equals(args[i]) && i + 1 < args.length) {
                deafAfter = args[++i];
            } else if ("--noisy".equals(args[i])) {
                noisy = true;
            }
        }

        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

        String line;
        while ((line = in.readLine()) != null) {
            String id = field(line, "id");
            String method = field(line, "method");
            if (method == null || id == null) {
                continue;
            }
            if (method.equals(exitOn)) {
                return;
            }
            if (method.equals(swallow)) {
                continue;
            }
            if (noisy) {
                err.println("fixture: handling " + method);
            }
            out.println(respond(id, method, line));
            if (method.equals(deafAfter)) {
                goDeaf();
            }
        }
    }

    /// Stays alive without reading standard input, until the client kills us.
    ///
    /// Returning from `main` would close the pipe and hand the client an error on
    /// its next write; the case worth exercising is the pipe that fills and never
    /// drains, which only a live process that refuses to read produces.
    private static void goDeaf() {
        try {
            // Nothing ever counts this down. The client kills the process when its
            // send deadline expires, which is the end this fixture is waiting for.
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String respond(String id, String method, String request) {
        return switch (method) {
            case "initialize" ->
                    """
                    {"jsonrpc":"2.0","id":"%s","result":{"protocolVersion":"2024-11-05",\
                    "serverInfo":{"name":"fake","version":"1"}}}"""
                            .formatted(id);
            case "tools/list" ->
                    """
                    {"jsonrpc":"2.0","id":"%s","result":{"tools":[{"name":"echo",\
                    "description":"Echo a message","inputSchema":{"type":"object",\
                    "properties":{"message":{"type":"string","description":"Text"}},\
                    "required":["message"]}}]}}"""
                            .formatted(id);
            case "tools/call" -> call(id, request);
            default ->
                    """
                    {"jsonrpc":"2.0","id":"%s","error":{"code":-32601,\
                    "message":"unknown method %s"}}"""
                            .formatted(id, method);
        };
    }

    /// Answers a tool call, including the `env` tool the isolation test reads.
    private static String call(String id, String request) {
        String message = field(request, "message");
        String text =
                "env".equals(field(request, "name")) ? envValue(message) : "echoed " + message;
        return """
               {"jsonrpc":"2.0","id":"%s","result":{"content":[{"type":"text","text":"%s"}]}}"""
                .formatted(id, text);
    }

    private static String envValue(String name) {
        String value = System.getenv(name);
        return value != null ? name + "=" + value : name + " is absent";
    }

    /// Reads one string field out of a JSON line without a JSON parser.
    ///
    /// The fixture must not depend on the library under test to decide what the
    /// library under test sent, and the shapes it sees are its own tests' making.
    private static String field(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
