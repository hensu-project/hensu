package io.hensu.mcp.config;

import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.util.MiniYaml;
import io.hensu.core.util.MiniYamlException;
import io.hensu.mcp.McpServerSpec;
import io.hensu.mcp.McpToolCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads the `mcp.yaml` grammar: which MCP servers a deployment wants started
/// or dialled.
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
///     tools:                # optional; the server's own names, before prefix:
///       allow: [read_file, list_directory]
///       deny: [write_file]
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
/// ### Two runtimes read it, each in its own dialect
/// The CLI reads `mcp.yaml` in {@link Dialect#CLI}. The relay reads its
/// `relay.yaml` in {@link Dialect#RELAY}, which is the same grammar minus the
/// three keys that describe a human-attended local run: `sandbox:`,
/// `unattended:` and `approval:`. A relay has no reviewer to ask and runs its
/// servers inside its own container, so each of the three is refused rather
/// than accepted and ignored, for the same reason a remote entry refuses
/// `sandbox:`. Everything else – `tools:` included – means the same in both, so
/// the two runtimes cannot disagree about what a declaration publishes.
///
/// ### `tools:` names what a server may publish
/// `allow:` lists the only tools published, `deny:` the tools never published;
/// both name tools the way the server lists them, before `prefix:` is applied.
/// Omitting `allow:` publishes everything not denied. An empty `allow:` and a
/// name in both lists are load errors, because each would make one of the two
/// lists mean nothing. {@link McpToolCatalog} applies the filter, and an entry
/// written with the prefix is reported with the spelling it should have; a
/// `deny:` entry written that way still withholds the tool it names.
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
/// @see McpServerSpec for the parsed shape
/// @see McpToolCatalog for where `prefix:` and `tools:` are applied
public final class McpConfig {

    /// Name of the file a CLI deployment declares its MCP servers in.
    public static final String FILE_NAME = "mcp.yaml";

    /// Which runtime a document is read for.
    ///
    /// ### Permitted Values
    /// - {@link #CLI} – `mcp.yaml`, read by the CLI for a run it may attend
    /// - {@link #RELAY} – `relay.yaml`, read by the tenant's relay, which no
    ///   reviewer attends and which contains its servers by its own container
    public enum Dialect {

        /// The CLI's `mcp.yaml`: every key of the grammar.
        CLI(FILE_NAME),

        /// The relay's `relay.yaml`: refuses `sandbox:`, `unattended:` and `approval:`.
        RELAY("relay.yaml");

        private final String document;

        Dialect(String document) {
            this.document = document;
        }

        /// Returns the file name a document of this dialect is read from.
        ///
        /// @return the file name, never null
        public String document() {
            return document;
        }
    }

    /// The only placeholder a launch argv may contain.
    public static final String WORKDIR_PLACEHOLDER = "{workdir}";

    private static final Logger logger = Logger.getLogger(McpConfig.class.getName());

    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");
    private static final Pattern LOOPBACK_V4 =
            Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

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
                    "tools",
                    "auth",
                    "headers",
                    "env",
                    "sandbox");
    private static final Set<String> AUTH_KEYS = Set.of("bearer");
    private static final Set<String> TOOLS_KEYS = Set.of("allow", "deny");
    private static final List<String> ATTENDED_KEYS = List.of("sandbox", "unattended", "approval");
    private static final Set<String> SANDBOX_KEYS = Set.of("network", "write", "cache");
    private static final Set<String> PACKAGE_RUNNERS = Set.of("npx", "uvx", "pnpx", "bunx");

    private McpConfig() {
        // Utility class
    }

    /// Loads the declarations from a working directory, tolerating absence.
    ///
    /// @param workingDirectory the directory holding the dialect's document, not null
    /// @param dialect which runtime the document is read for, not null
    /// @return the declared servers in document order, never null (may be empty)
    /// @throws MiniYamlException if the file exists but does not parse or does
    ///     not conform, carrying the offending line
    /// @throws java.io.UncheckedIOException if the file exists but cannot be read
    public static List<McpServerSpec> load(Path workingDirectory, Dialect dialect) {
        Path file = workingDirectory.resolve(dialect.document());
        if (!Files.exists(file)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return parse(content, workingDirectory, dialect);
    }

    /// Parses a declaration document.
    ///
    /// @param content the complete document text, not null
    /// @param workingDirectory the directory `{workdir}` expands to, not null
    /// @param dialect which runtime the document is read for, not null
    /// @return the declared servers in document order, never null (may be empty)
    /// @throws MiniYamlException if the document does not parse or does not conform
    public static List<McpServerSpec> parse(
            String content, Path workingDirectory, Dialect dialect) {
        Path workingDir = workingDirectory.toAbsolutePath().normalize();
        MiniYaml.Mapping root = MiniYaml.parse(content);
        if (!root.has("servers")) {
            logger.warning(
                    dialect.document()
                            + " declares no 'servers:' block; no MCP tools are available");
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
            servers.add(compile(id, entry.asMapping(), workingDir, dialect));
        }
        return List.copyOf(servers);
    }

    // --------------------------------------------------------------- compiling

    private static McpServerSpec compile(
            String id, MiniYaml.Mapping entry, Path workingDir, Dialect dialect) {
        rejectUnknownKeys("server '" + id + "'", entry, SERVER_KEYS);
        if (dialect == Dialect.RELAY) {
            rejectAttendedKeys(id, entry);
        }
        return entry.has("url")
                ? compileHttp(id, entry, dialect)
                : compileStdio(id, entry, workingDir, dialect);
    }

    private static McpServerSpec compileStdio(
            String id, MiniYaml.Mapping entry, Path workingDir, Dialect dialect) {
        rejectRemoteOnlyKeys(id, entry);

        List<String> command = command(id, entry, workingDir);
        SandboxPolicy sandbox =
                entry.has("sandbox")
                        ? sandboxPolicy(id, entry.require("sandbox").asMapping(), workingDir)
                        : SandboxPolicy.restrictive();
        if (dialect == Dialect.CLI) {
            warnAboutOfflinePackageRunner(id, command, sandbox, entry.line());
        }

        return new McpServerSpec.Stdio(
                id,
                prefix(id, entry),
                toolFilter(id, entry),
                command,
                environment(id, entry),
                sandbox,
                startupTimeout(entry),
                requestTimeout(entry),
                unattended(entry),
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

    private static McpServerSpec compileHttp(String id, MiniYaml.Mapping entry, Dialect dialect) {
        MiniYaml.Value declared = entry.require("url");
        rejectLocalOnlyKeys(id, entry, declared.line());

        URI url = endpoint(id, declared);
        return new McpServerSpec.Http(
                id,
                prefix(id, entry),
                toolFilter(id, entry),
                url,
                bearerKey(id, entry, dialect),
                headers(id, entry),
                startupTimeout(entry),
                requestTimeout(entry),
                unattended(entry),
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

    /// Refuses, in the relay's dialect, the keys that describe an attended local run.
    ///
    /// A relay asks no reviewer and launches its servers inside the container it
    /// already runs in, so a `sandbox:`, `unattended:` or `approval:` would promise
    /// a containment or a consent nobody provides.
    private static void rejectAttendedKeys(String id, MiniYaml.Mapping entry) {
        for (String key : ATTENDED_KEYS) {
            if (entry.has(key)) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares "
                                + key
                                + ": in "
                                + Dialect.RELAY.document()
                                + "; the relay asks no reviewer and contains its servers by"
                                + " its own container, so the key would promise something"
                                + " nobody provides",
                        entry.require(key).line());
            }
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

    /// Whether a host is the loopback interface, judged from its spelling alone.
    ///
    /// An address in `127.0.0.0/8` must be a literal: a DNS name such as
    /// `127.example.com` resolves wherever its owner points it, so matching on
    /// the prefix would send plaintext credentials across the network.
    private static boolean isLoopback(String host) {
        String bare = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return bare.equals("localhost")
                || bare.equals("::1")
                || LOOPBACK_V4.matcher(bare).matches();
    }

    private static String bearerKey(String id, MiniYaml.Mapping entry, Dialect dialect) {
        if (!entry.has("auth")) {
            return null;
        }
        MiniYaml.Value block = entry.require("auth");
        MiniYaml.Mapping auth = block.asMapping();
        rejectUnknownKeys("auth block of server '" + id + "'", auth, AUTH_KEYS);
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
                            + (dialect == Dialect.CLI
                                    ? ", and store the value with 'hensu credentials set'"
                                    : ", and supply the value as an environment variable or a"
                                            + " file of that name in the relay's secrets"
                                            + " directory"),
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
        if (!McpToolCatalog.TOOL_NAME.matcher(prefix).matches()) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares prefix: '"
                            + prefix
                            + "', which must match "
                            + McpToolCatalog.TOOL_NAME.pattern()
                            + " so a prefixed tool name stays a legal tool name",
                    value.line());
        }
        return prefix;
    }

    private static McpServerSpec.ToolFilter toolFilter(String id, MiniYaml.Mapping entry) {
        if (!entry.has("tools")) {
            return McpServerSpec.ToolFilter.ALL;
        }
        MiniYaml.Mapping block = entry.require("tools").asMapping();
        rejectUnknownKeys("tools block of server '" + id + "'", block, TOOLS_KEYS);
        Set<String> allow = toolNames(id, block, "allow");
        Set<String> deny = toolNames(id, block, "deny");
        if (block.has("allow") && allow.isEmpty()) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares an empty tools.allow, which would publish nothing;"
                            + " omit allow: to publish every tool not denied",
                    block.require("allow").line());
        }
        for (String name : allow) {
            if (deny.contains(name)) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' both allows and denies tool '"
                                + name
                                + "'; name it in one list",
                        block.require("deny").line());
            }
        }
        return new McpServerSpec.ToolFilter(allow, deny);
    }

    private static Set<String> toolNames(String id, MiniYaml.Mapping block, String key) {
        if (!block.has(key)) {
            return Set.of();
        }
        MiniYaml.Value value = block.require(key);
        Set<String> names = new LinkedHashSet<>();
        for (String name : value.asStringList()) {
            if (!McpToolCatalog.TOOL_NAME.matcher(name).matches()) {
                throw new MiniYamlException(
                        "server '"
                                + id
                                + "' declares tools."
                                + key
                                + " entry '"
                                + name
                                + "', which is not a legal tool name ("
                                + McpToolCatalog.TOOL_NAME.pattern()
                                + ")",
                        value.line());
            }
            names.add(name);
        }
        return names;
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

    private static long startupTimeout(MiniYaml.Mapping entry) {
        return entry.has("startup")
                ? entry.require("startup").asLong()
                : McpServerSpec.DEFAULT_STARTUP_TIMEOUT_MS;
    }

    private static long requestTimeout(MiniYaml.Mapping entry) {
        return entry.has("timeout")
                ? entry.require("timeout").asLong()
                : McpServerSpec.DEFAULT_REQUEST_TIMEOUT_MS;
    }

    private static boolean unattended(MiniYaml.Mapping entry) {
        return entry.has("unattended") && entry.require("unattended").asBoolean();
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
        rejectUnknownKeys("sandbox block of server '" + id + "'", sandbox, SANDBOX_KEYS);
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

    /// Refuses any key of a mapping outside the set its block accepts.
    ///
    /// @param subject what declares the mapping, as the message names it, e.g.
    ///     `server 'acme'` or `auth block of server 'acme'`
    private static void rejectUnknownKeys(
            String subject, MiniYaml.Mapping mapping, Set<String> known) {
        for (String key : mapping.keys()) {
            if (!known.contains(key)) {
                throw new MiniYamlException(
                        subject + " declares unknown key '" + key + "', expected one of " + known,
                        mapping.get(key).line());
            }
        }
    }
}
