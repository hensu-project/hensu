package io.hensu.cli.tool;

import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.util.MiniYaml;
import io.hensu.core.util.MiniYamlException;
import io.hensu.mcp.McpServerSpec;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads `mcp.yaml`: which MCP servers a deployment wants started locally.
///
/// The grammar is deliberately smaller than the command catalog's, because a
/// server is one decision rather than a family of parameterised invocations:
///
/// ```yaml
/// servers:
///   filesystem:                          # a process this run launches
///     command: ["/usr/local/bin/mcp-server-filesystem", "{workdir}"]
///     startup: 30000        # ms allowed for launch + handshake, optional
///     timeout: 30000        # per-request ms, optional
///     unattended: true      # optional, default false
///     approval: required    # optional, default none
///     prefix: "fs_"         # optional, prepended to every published tool name
///     env:
///       LOG_LEVEL: warn
///     sandbox:
///       network: false
///       write: ["."]
///   acme:                                # a remote endpoint this run dials
///     url: "https://mcp.acme.example/mcp"
///     auth: {bearer: HENSU_MCP_ACME_TOKEN}   # a credential KEY, never a token
///     headers:
///       X-Acme-Region: eu-west-1
///     prefix: "acme_"
///     timeout: 20000
/// ```
///
/// ### A remote entry refuses `sandbox:` and `env:` rather than ignoring them
/// There is no local process to contain and none to give an environment to.
/// Accepting either block and quietly doing nothing with it would tell an
/// operator that containment applies to a call that leaves the machine, which is
/// the one thing Hensu cannot provide for a remote server. The bound that does
/// exist is the set of hosts declared across this document, enforced when a
/// remote endpoint tries to redirect a call somewhere else.
///
/// ### `auth:` names a key, never a token
/// `auth: {bearer: HENSU_MCP_ACME_TOKEN}` names an entry in the operator's
/// credential store, resolved at launch. A value that looks like a literal token
/// is a load error: every real token format fails the screaming-snake-case rule
/// on its first character or its punctuation, so the check costs nothing and
/// catches a secret about to be committed to a file in the project.
///
/// ### `prefix:` is how two servers keep the same tool name
/// It is prepended verbatim, so `prefix: "acme_"` turns `search` into
/// `acme_search`. It complements rather than replaces the first-wins notice: two
/// servers publishing `search` with no prefix between them still collide, and the
/// operator is still told which one lost the name.
///
/// ### `{workdir}` is the only substitution
/// It expands to the absolute working directory as one whole argv token. No
/// other placeholder exists, and an unknown one is a load error naming its line.
/// Agent data never reaches a launch argv: an agent picks tools, never servers,
/// so there is nothing here for a model to influence.
///
/// ### `startup:` and `timeout:` are separate on purpose
/// A server's first answer costs whatever its runtime costs to boot – `npx`
/// resolving a package, an interpreter starting – while every answer after it
/// is a round trip to a process already running. An operator who wants a wedged
/// server to fail a node in a second sets `timeout: 1000`; if that number also
/// had to cover the launch, the server would never start. `startup:` buys the
/// launch its own patience and defaults to
/// {@link McpServerSpec#DEFAULT_STARTUP_TIMEOUT_MS}.
///
/// ### A package runner needs the network it was denied
/// `npx some-mcp-server` resolves and downloads on first run, so declaring it
/// under `network: false` produces a server that cannot start. That is a warning
/// rather than an error, because a warm package cache mounted through `cache:`
/// makes the offline form legal, and refusing it would outlaw the one shape that
/// is both offline and reproducible.
///
/// ### Errors carry a line number, absence does not
/// A malformed file is a {@link MiniYamlException} naming the line an operator
/// has to fix. A *missing* file is not an error at all: a deployment that wired
/// no servers gets no tools, which is the normal case.
///
/// ### The executable is not resolved at load
/// Unlike the command catalog, which fails outright when an entry names a
/// binary that does not exist, a server that is not installed simply does not
/// start. Its tools are absent and the run says so, because an MCP server is a
/// runtime dependency of the deployment rather than part of the grant the
/// catalog expresses.
///
/// @implNote **Immutable after construction.** Pure functions over a parsed
/// document; safe to call from any thread.
/// @see DeclaredMcpToolProvider for what starts from these declarations
/// @see McpServerSpec for the parsed shape
public final class McpConfig {

    /// Name of the file a deployment declares its MCP servers in.
    public static final String FILE_NAME = "mcp.yaml";

    /// The only placeholder a launch argv may contain.
    public static final String WORKDIR_PLACEHOLDER = "{workdir}";

    private static final Logger logger = Logger.getLogger(McpConfig.class.getName());

    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /// Shape a tool name, and therefore a prefix, has to keep.
    public static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    /// Shape a credential key has to take: screaming snake case.
    ///
    /// Every real token format – `sk-…`, `ghp_…`, a JWT, base64 – fails this on
    /// its first character or its punctuation, which is the point.
    private static final Pattern CREDENTIAL_KEY = Pattern.compile("[A-Z][A-Z0-9_]{2,64}");

    private static final Set<String> SERVER_KEYS =
            Set.of(
                    "command",
                    "url",
                    "startup",
                    "timeout",
                    "unattended",
                    "approval",
                    "prefix",
                    "auth",
                    "headers",
                    "env",
                    "sandbox");
    private static final Set<String> AUTH_KEYS = Set.of("bearer");
    private static final Set<String> SANDBOX_KEYS = Set.of("network", "write", "cache");
    private static final Set<String> PACKAGE_RUNNERS = Set.of("npx", "uvx", "pnpx", "bunx");

    private McpConfig() {
        // Utility class
    }

    /// Loads the declarations from a working directory, tolerating absence.
    ///
    /// @param workingDirectory the directory holding `mcp.yaml`, not null
    /// @return the declared servers in document order, never null (may be empty)
    /// @throws MiniYamlException if the file exists but does not parse or does
    ///     not conform, carrying the offending line
    /// @throws java.io.UncheckedIOException if the file exists but cannot be read
    public static List<McpServerSpec> load(Path workingDirectory) {
        Path file = workingDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return parse(content, workingDirectory);
    }

    /// Parses a declaration document.
    ///
    /// @param content the complete `mcp.yaml` text, not null
    /// @param workingDirectory the directory `{workdir}` expands to, not null
    /// @return the declared servers in document order, never null (may be empty)
    /// @throws MiniYamlException if the document does not parse or does not conform
    public static List<McpServerSpec> parse(String content, Path workingDirectory) {
        Path workingDir = workingDirectory.toAbsolutePath().normalize();
        MiniYaml.Mapping root = MiniYaml.parse(content);
        if (!root.has("servers")) {
            logger.warning(FILE_NAME + " declares no 'servers:' block; no MCP tools are available");
            return List.of();
        }

        MiniYaml.Mapping block = root.require("servers").asMapping();
        List<McpServerSpec> servers = new ArrayList<>();
        for (String id : block.keys()) {
            MiniYaml.Value entry = block.get(id);
            if (!SERVER_ID.matcher(id).matches()) {
                throw new MiniYamlException(
                        "server id '" + id + "' must match " + SERVER_ID.pattern(), entry.line());
            }
            servers.add(compile(id, entry.asMapping(), workingDir));
        }
        return List.copyOf(servers);
    }

    // --------------------------------------------------------------- compiling

    private static McpServerSpec compile(String id, MiniYaml.Mapping entry, Path workingDir) {
        rejectUnknownKeys(id, entry);
        return entry.has("url") ? compileHttp(id, entry) : compileStdio(id, entry, workingDir);
    }

    private static McpServerSpec compileStdio(String id, MiniYaml.Mapping entry, Path workingDir) {
        rejectRemoteOnlyKeys(id, entry);

        List<String> command = command(id, entry, workingDir);
        SandboxPolicy sandbox =
                entry.has("sandbox")
                        ? sandboxPolicy(id, entry.require("sandbox").asMapping(), workingDir)
                        : SandboxPolicy.restrictive();
        warnAboutOfflinePackageRunner(id, command, sandbox, entry.line());

        return new McpServerSpec.Stdio(
                id,
                prefix(id, entry),
                command,
                environment(id, entry),
                sandbox,
                entry.has("startup")
                        ? entry.require("startup").asLong()
                        : McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS,
                entry.has("timeout")
                        ? entry.require("timeout").asLong()
                        : McpServerSpec.DEFAULT_REQUEST_TIMEOUT_MS,
                entry.has("unattended") && entry.require("unattended").asBoolean(),
                approvalRequired(id, entry));
    }

    private static List<String> command(String id, MiniYaml.Mapping entry, Path workingDir) {
        MiniYaml.Value value = entry.require("command");
        List<String> declared = value.asStringList();
        if (declared.isEmpty()) {
            throw new MiniYamlException(
                    "server '" + id + "' declares an empty command", value.line());
        }
        List<String> argv = new ArrayList<>();
        for (String element : declared) {
            argv.add(expand(id, element, workingDir, value.line()));
        }
        return argv;
    }

    /// Expands `{workdir}` and refuses every other placeholder.
    private static String expand(String id, String element, Path workingDir, int line) {
        Matcher matcher = PLACEHOLDER.matcher(element);
        if (!matcher.find()) {
            return element;
        }
        if (!WORKDIR_PLACEHOLDER.equals(element)) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares '"
                            + element
                            + "'; "
                            + WORKDIR_PLACEHOLDER
                            + " is the only placeholder and it must be a whole argv element",
                    line);
        }
        return workingDir.toString();
    }

    private static McpServerSpec compileHttp(String id, MiniYaml.Mapping entry) {
        MiniYaml.Value declared = entry.require("url");
        rejectLocalOnlyKeys(id, entry, declared.line());

        URI url = endpoint(id, declared);
        return new McpServerSpec.Http(
                id,
                prefix(id, entry),
                url,
                bearerKey(id, entry),
                headers(id, entry),
                entry.has("startup")
                        ? entry.require("startup").asLong()
                        : McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS,
                entry.has("timeout")
                        ? entry.require("timeout").asLong()
                        : McpServerSpec.DEFAULT_REQUEST_TIMEOUT_MS,
                entry.has("unattended") && entry.require("unattended").asBoolean(),
                approvalRequired(id, entry));
    }

    /// Refuses the keys that only make sense for a process Hensu owns.
    ///
    /// Each is rejected rather than ignored, and the message says why: accepting
    /// a `sandbox:` block on an endpoint nobody here launched would imply a
    /// containment that does not exist.
    private static void rejectLocalOnlyKeys(String id, MiniYaml.Mapping entry, int line) {
        if (entry.has("command")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares both url: and command:; a server is either a process"
                            + " this run launches or an endpoint it dials, never both",
                    line);
        }
        if (entry.has("sandbox")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares url: with sandbox:; there is no local process to"
                            + " contain, and accepting the block would imply containment Hensu"
                            + " cannot provide for a remote endpoint",
                    entry.require("sandbox").line());
        }
        if (entry.has("env")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares url: with env:; there is no local process to give an"
                            + " environment to. Use headers: for values the endpoint should"
                            + " receive, and auth: for a credential key",
                    entry.require("env").line());
        }
    }

    /// Refuses the keys that only make sense for an endpoint Hensu dials.
    private static void rejectRemoteOnlyKeys(String id, MiniYaml.Mapping entry) {
        if (entry.has("auth")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares auth: without url:; a launched server"
                            + " authenticates through env:, not through a bearer token",
                    entry.require("auth").line());
        }
        if (entry.has("headers")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares headers: without url:; the stdio transport"
                            + " carries no headers",
                    entry.require("headers").line());
        }
    }

    private static URI endpoint(String id, MiniYaml.Value declared) {
        String text = declared.asString();
        URI url;
        try {
            url = new URI(text);
        } catch (URISyntaxException e) {
            throw new MiniYamlException(
                    "server '" + id + "' declares the unusable url '" + text + "'",
                    declared.line());
        }
        if (!url.isAbsolute() || url.getHost() == null) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares url: '"
                            + text
                            + "' which is not an absolute http(s) URL",
                    declared.line());
        }
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares url: scheme '"
                            + scheme
                            + "'; Streamable HTTP is https:, or http: on loopback",
                    declared.line());
        }
        if (scheme.equals("http") && !isLoopback(url.getHost())) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares a plaintext url: to "
                            + url.getHost()
                            + "; http: is accepted only on loopback, because every argument and"
                            + " every credential on this connection would cross the network in"
                            + " the clear",
                    declared.line());
        }
        return url;
    }

    private static boolean isLoopback(String host) {
        String bare = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return bare.equals("localhost") || bare.equals("::1") || bare.startsWith("127.");
    }

    private static String bearerKey(String id, MiniYaml.Mapping entry) {
        if (!entry.has("auth")) {
            return null;
        }
        MiniYaml.Value block = entry.require("auth");
        MiniYaml.Mapping auth = block.asMapping();
        for (String key : auth.keys()) {
            if (!AUTH_KEYS.contains(key)) {
                throw new MiniYamlException(
                        "auth block of server '"
                                + id
                                + "' declares unknown key '"
                                + key
                                + "', expected one of "
                                + AUTH_KEYS,
                        auth.get(key).line());
            }
        }
        if (!auth.has("bearer")) {
            return null;
        }
        MiniYaml.Value bearer = auth.require("bearer");
        String key = bearer.asString();
        if (!CREDENTIAL_KEY.matcher(key).matches()) {
            // The value is deliberately left out of the message. The likeliest reason this
            // check fails is a real token pasted where its key belongs, and quoting it
            // would copy that token onto the console and into every CI log that captures it.
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares an auth.bearer value that is not a credential key and"
                            + " may be a token; it is not repeated here. Name a key in the"
                            + " credential store instead, matching "
                            + CREDENTIAL_KEY.pattern()
                            + ", and store the value with 'hensu credentials set'",
                    bearer.line());
        }
        return key;
    }

    private static Map<String, String> headers(String id, MiniYaml.Mapping entry) {
        if (!entry.has("headers")) {
            return Map.of();
        }
        MiniYaml.Mapping declared = entry.require("headers").asMapping();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : declared.keys()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("authorization")) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares an Authorization header; use auth: so the token"
                                + " stays a credential key rather than a literal in this file",
                        declared.get(name).line());
            }
            if (lower.startsWith("mcp-")) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares the reserved header '"
                                + name
                                + "'; the transport owns the Mcp-* namespace, and a header that"
                                + " disagrees with the request body is a protocol error on the"
                                + " wire",
                        declared.get(name).line());
            }
            headers.put(name, declared.get(name).asString());
        }
        return headers;
    }

    private static String prefix(String id, MiniYaml.Mapping entry) {
        if (!entry.has("prefix")) {
            return "";
        }
        MiniYaml.Value value = entry.require("prefix");
        String prefix = value.asString();
        if (!TOOL_NAME.matcher(prefix).matches()) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares prefix: '"
                            + prefix
                            + "', which must match "
                            + TOOL_NAME.pattern()
                            + " so a prefixed tool name stays a legal tool name",
                    value.line());
        }
        return prefix;
    }

    private static Map<String, String> environment(String id, MiniYaml.Mapping entry) {
        if (!entry.has("env")) {
            return Map.of();
        }
        MiniYaml.Mapping env = entry.require("env").asMapping();
        Map<String, String> variables = new LinkedHashMap<>();
        for (String key : env.keys()) {
            if (key.startsWith(ParamSpec.ENV_PREFIX)) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares env: key '"
                                + key
                                + "'; the "
                                + ParamSpec.ENV_PREFIX
                                + " namespace is reserved for command parameters",
                        env.get(key).line());
            }
            variables.put(key, env.get(key).asString());
        }
        return variables;
    }

    private static boolean approvalRequired(String id, MiniYaml.Mapping entry) {
        if (!entry.has("approval")) {
            return false;
        }
        MiniYaml.Value value = entry.require("approval");
        return switch (value.asString()) {
            case "required" -> true;
            case "none" -> false;
            default ->
                    throw new MiniYamlException(
                            "server '"
                                    + id
                                    + "' declares approval: '"
                                    + value.asString()
                                    + "', expected required or none",
                            value.line());
        };
    }

    private static SandboxPolicy sandboxPolicy(
            String id, MiniYaml.Mapping sandbox, Path workingDir) {
        for (String key : sandbox.keys()) {
            if (!SANDBOX_KEYS.contains(key)) {
                throw new MiniYamlException(
                        "sandbox block of server '"
                                + id
                                + "' declares unknown key '"
                                + key
                                + "', expected one of "
                                + SANDBOX_KEYS,
                        sandbox.get(key).line());
            }
        }
        boolean network = sandbox.has("network") && sandbox.require("network").asBoolean();
        List<String> writePaths = new ArrayList<>();
        if (sandbox.has("write")) {
            MiniYaml.Value value = sandbox.require("write");
            for (String entry : value.asStringList()) {
                writePaths.add(validateWritePath(id, entry, workingDir, value.line()));
            }
        }
        List<String> cachePaths = new ArrayList<>();
        if (sandbox.has("cache")) {
            MiniYaml.Value value = sandbox.require("cache");
            for (String entry : value.asStringList()) {
                cachePaths.add(validateCachePath(id, entry, value.line()));
            }
        }
        return new SandboxPolicy(network, writePaths, cachePaths);
    }

    private static String validateWritePath(String id, String entry, Path workingDir, int line) {
        Path resolved;
        try {
            resolved = workingDir.resolve(entry).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new MiniYamlException(
                    "server '" + id + "' declares the unusable path '" + entry + "'", line);
        }
        if (!resolved.startsWith(workingDir)) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares write: '"
                            + entry
                            + "' which resolves outside the working directory "
                            + workingDir
                            + "; use cache: for host directories outside the project",
                    line);
        }
        return entry;
    }

    private static String validateCachePath(String id, String entry, int line) {
        String expanded = expandTilde(entry);
        Path resolved;
        try {
            resolved = Path.of(expanded);
        } catch (InvalidPathException e) {
            throw new MiniYamlException(
                    "server '" + id + "' declares the unusable path '" + entry + "'", line);
        }
        if (!resolved.isAbsolute()) {
            throw new MiniYamlException(
                    "server '" + id + "' declares cache: '" + entry + "' which is not absolute",
                    line);
        }
        return expanded;
    }

    private static String expandTilde(String entry) {
        if (!entry.startsWith("~")) {
            return entry;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return entry;
        }
        return entry.length() == 1 ? home : home + entry.substring(1);
    }

    /// Warns when a package runner is denied the network it needs to resolve.
    private static void warnAboutOfflinePackageRunner(
            String id, List<String> command, SandboxPolicy sandbox, int line) {
        String binary = Path.of(command.getFirst()).getFileName().toString();
        if (PACKAGE_RUNNERS.contains(binary)
                && !sandbox.network()
                && sandbox.cachePaths().isEmpty()) {
            logger.warning(
                    FILE_NAME
                            + " line "
                            + line
                            + ": server '"
                            + id
                            + "' launches through "
                            + binary
                            + " under network: false and mounts no cache:, so the package cannot"
                            + " be resolved. Declare network: true, or mount a warm cache.");
        }
    }

    private static void rejectUnknownKeys(String id, MiniYaml.Mapping entry) {
        for (String key : entry.keys()) {
            if (!SERVER_KEYS.contains(key)) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares unknown key '"
                                + key
                                + "', expected one of "
                                + SERVER_KEYS,
                        entry.get(key).line());
            }
        }
    }
}
