package io.hensu.cli.tool;

import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.util.MiniYaml;
import io.hensu.core.util.MiniYamlException;
import io.hensu.mcp.McpServerSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
///   filesystem:
///     command: ["/usr/local/bin/mcp-server-filesystem", "{workdir}"]
///     timeout: 30000        # per-request ms, optional
///     unattended: true      # optional, default false
///     approval: required    # optional, default none
///     env:
///       LOG_LEVEL: warn
///     sandbox:
///       network: false
///       write: ["."]
/// ```
///
/// ### `{workdir}` is the only substitution
/// It expands to the absolute working directory as one whole argv token. No
/// other placeholder exists, and an unknown one is a load error naming its line.
/// Agent data never reaches a launch argv: an agent picks tools, never servers,
/// so there is nothing here for a model to influence.
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
/// @see LocalMcpToolProvider for what starts from these declarations
/// @see McpServerSpec for the parsed shape
public final class McpConfig {

    /// Name of the file a deployment declares its MCP servers in.
    public static final String FILE_NAME = "mcp.yaml";

    /// The only placeholder a launch argv may contain.
    public static final String WORKDIR_PLACEHOLDER = "{workdir}";

    private static final Logger logger = Logger.getLogger(McpConfig.class.getName());

    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");
    private static final Set<String> SERVER_KEYS =
            Set.of("command", "url", "timeout", "unattended", "approval", "env", "sandbox");
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
        rejectUrl(id, entry);

        List<String> command = command(id, entry, workingDir);
        SandboxPolicy sandbox =
                entry.has("sandbox")
                        ? sandboxPolicy(id, entry.require("sandbox").asMapping(), workingDir)
                        : SandboxPolicy.restrictive();
        warnAboutOfflinePackageRunner(id, command, sandbox, entry.line());

        return new McpServerSpec(
                id,
                command,
                environment(id, entry),
                sandbox,
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

    private static void rejectUrl(String id, MiniYaml.Mapping entry) {
        if (entry.has("url")) {
            throw new MiniYamlException(
                    "server '"
                            + id
                            + "' declares url:; HTTP MCP endpoints are not yet supported on the"
                            + " CLI, declare a stdio command: instead",
                    entry.require("url").line());
        }
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
