package io.hensu.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
/// - `--swallow-tool <name>` – the same for `tools/call` of one tool only, so a
///   test can still call another tool afterwards
/// - `--swallow-receipt <path>` – create that file when a swallowed tool call has
///   been read, so a test knows the request crossed the pipe before it acts on it
/// - `--noisy` – write a line to standard error before answering, proving the
///   client drains it instead of deadlocking on a full pipe
/// - `--exit-on <method>` – exit without answering that method, so a caller can
///   exercise a server that dies in the middle of a run
/// - `--deaf-after <method>` – answer that method, then stay alive without ever
///   reading standard input again, so the pipe to the server fills and the
///   client's send blocks instead of its reply
/// - `--mute-after <method>` – answer that method, then close standard output
///   while staying alive and reading, so the client's reader ends but the process
///   does not
/// - `--no-revision` – answer `initialize` without a `protocolVersion`, as a
///   server that does not conform would
/// - `--no-tools-capability` – answer `initialize` without a `tools` capability,
///   and answer a `tools/list` sent anyway with the usual catalog, so a client
///   that lists tools regardless is caught publishing them
/// - `--slow-start <ms>` – sleep that long before answering `initialize`, standing
///   in for a runtime that is expensive to boot. Real startup cost is whatever
///   the host's JVM happens to charge, which is not a number a test can assert
///   against; a declared sleep makes the launch reliably slower than a per-request
///   budget on every machine
///
/// Calling the tool named `env` reports whether one variable reached the child,
/// which is how the environment-isolation test reads the launched process.
/// Calling the tool named `fail` is answered with a JSON-RPC error carrying
/// `data`, as a server rejecting the call's arguments would.
/// Calling the tool named `cancelled` reports the request ids every
/// `notifications/cancelled` so far named, which is how the cancellation test
/// reads what crossed the pipe.
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
        String swallowTool = null;
        Path swallowReceipt = null;
        String exitOn = null;
        String deafAfter = null;
        String muteAfter = null;
        long slowStartMs = 0;
        boolean noisy = false;
        boolean announcesRevision = true;
        boolean declaresTools = true;
        for (int i = 0; i < args.length; i++) {
            if ("--swallow".equals(args[i]) && i + 1 < args.length) {
                swallow = args[++i];
            } else if ("--swallow-tool".equals(args[i]) && i + 1 < args.length) {
                swallowTool = args[++i];
            } else if ("--swallow-receipt".equals(args[i]) && i + 1 < args.length) {
                swallowReceipt = Path.of(args[++i]);
            } else if ("--exit-on".equals(args[i]) && i + 1 < args.length) {
                exitOn = args[++i];
            } else if ("--deaf-after".equals(args[i]) && i + 1 < args.length) {
                deafAfter = args[++i];
            } else if ("--mute-after".equals(args[i]) && i + 1 < args.length) {
                muteAfter = args[++i];
            } else if ("--slow-start".equals(args[i]) && i + 1 < args.length) {
                slowStartMs = Long.parseLong(args[++i]);
            } else if ("--noisy".equals(args[i])) {
                noisy = true;
            } else if ("--no-revision".equals(args[i])) {
                announcesRevision = false;
            } else if ("--no-tools-capability".equals(args[i])) {
                declaresTools = false;
            }
        }

        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);

        List<String> cancelled = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            String id = field(line, "id");
            String method = field(line, "method");
            if ("notifications/cancelled".equals(method)) {
                cancelled.add(field(line, "requestId"));
                continue;
            }
            if (method == null || id == null) {
                continue;
            }
            if ("tools/call".equals(method)
                    && swallowTool != null
                    && swallowTool.equals(field(line, "name"))) {
                if (swallowReceipt != null) {
                    Files.writeString(swallowReceipt, id);
                }
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
            if (slowStartMs > 0 && "initialize".equals(method)) {
                sleep(slowStartMs);
            }
            out.println(respond(id, method, line, cancelled, announcesRevision, declaresTools));
            if (method.equals(deafAfter)) {
                goDeaf();
            }
            if (method.equals(muteAfter)) {
                out.close();
                // Alive and reading, never answering: only the client closing our
                // standard input ends this.
                in.transferTo(Writer.nullWriter());
                return;
            }
        }
    }

    /// Spends the declared time before answering, as a slow runtime would.
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static String respond(
            String id,
            String method,
            String request,
            List<String> cancelled,
            boolean announcesRevision,
            boolean declaresTools) {
        return switch (method) {
            case "initialize" ->
                    """
                    {"jsonrpc":"2.0","id":"%s","result":{%s%s\
                    "serverInfo":{"name":"fake","version":"1"}}}"""
                            .formatted(
                                    id,
                                    announcesRevision ? "\"protocolVersion\":\"2024-11-05\"," : "",
                                    declaresTools ? "\"capabilities\":{\"tools\":{}}," : "");
            case "tools/list" ->
                    """
                    {"jsonrpc":"2.0","id":"%s","result":{"tools":[{"name":"echo",\
                    "description":"Echo a message","inputSchema":{"type":"object",\
                    "properties":{"message":{"type":"string","description":"Text"}},\
                    "required":["message"]}}]}}"""
                            .formatted(id);
            case "tools/call" -> call(id, request, cancelled);
            default ->
                    """
                    {"jsonrpc":"2.0","id":"%s","error":{"code":-32601,\
                    "message":"unknown method %s"}}"""
                            .formatted(id, method);
        };
    }

    /// Answers a tool call, including the `env` and `cancelled` tools tests read.
    private static String call(String id, String request, List<String> cancelled) {
        if ("fail".equals(field(request, "name"))) {
            return """
                   {"jsonrpc":"2.0","id":"%s","error":{"code":-32602,"message":"Invalid params",\
                   "data":{"tool":"fail"}}}"""
                    .formatted(id);
        }
        String message = field(request, "message");
        String text =
                switch (String.valueOf(field(request, "name"))) {
                    case "env" -> envValue(message);
                    case "cancelled" -> "cancelled " + String.join(",", cancelled);
                    default -> "echoed " + message;
                };
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
