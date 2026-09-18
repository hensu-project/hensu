package io.hensu.core.execution.action;

import io.hensu.core.util.MiniYaml;
import io.hensu.core.util.MiniYamlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// The catalog of commands a deployment permits, loaded from `commands.yaml`.
///
/// The catalog *is* the allowlist. Workflows and agents name a command by id and
/// never supply command text, so the set of binaries that can run is exactly the
/// set a human wrote down. Everything the catalog can decide is decided here, at
/// load time: argv templates are compiled, executables are resolved to absolute
/// paths, shell-mode restrictions are enforced, and sandbox scopes are checked.
/// A catalog that breaks a rule fails to load with the offending line, rather
/// than failing later in front of an agent.
///
/// ### Grammar
/// {@snippet lang=yaml :
/// commands:
///   run-tests:
///     description: "Run the suite so the implementer can check its own work"
///     exec: ["./gradlew", "test", "{gradle_args}"]
///     timeout: 120000
///     env:
///       CI: "true"
///     tool:
///       description: "Run the project test suite"
///       params:
///         gradle_args: { type: string, required: false, pattern: "^[-A-Za-z0-9=._:]*$" }
///     sandbox:
///       network: false
///       write: ["build/", ".gradle/"]
///       cache: ["~/.gradle/caches"]
///     unattended: true
///     approval: none
///
///   shell-rung:
///     description: "Ad-hoc operations no catalog can enumerate; closed policy"
///     rung: true
///     tool:
///       description: "Run one shell command line in the working directory"
/// }
///
/// A command declares exactly one of `exec:`, `shell: true` with `command:`, and
/// `rung: true`; the single-string `command:` form carries no argv structure and
/// is rejected. Without a `tool:` block a command stays invisible to agents while
/// remaining usable by workflow-authored `execute(...)` actions.
///
/// ### The rung
/// `rung: true` is the one form whose command line the agent writes. A catalog
/// enumerates the operations a deployment knows it wants; it cannot enumerate the
/// tail of a general capability, and an unattended run has no operator to add an
/// entry mid-run. The rung does not exist unless an operator declared it, and its
/// policy is fixed rather than merged – `sandbox:` and `env:` are rejected on it,
/// so elevation stays reachable only through named entries whose text a human
/// wrote.
///
/// ### Shell text that pipes
/// A pipeline reports its last stage's status, so `./gradlew test | tail -40`
/// succeeds when the build fails. An entry whose text contains `|` must declare
/// `pipeline: last-stage-status` to say the author knows.
///
/// Every entry must carry a `description:`. It addresses a human reader deciding
/// whether the entry is still needed, which is a different question from the one
/// `tool: description:` answers for a model deciding whether to call it.
///
/// @implNote **Mutable.** {@link #registerCommand} exists for programmatic
/// catalogs in tests and embedders; instances loaded from a file are not mutated
/// afterwards. External synchronization is required if a catalog is mutated
/// while workflows read it.
/// @see CommandDefinition for the compiled entry
/// @see CommandConfigException for the failure carrying the source line
public class CommandRegistry {

    private static final Logger logger = Logger.getLogger(CommandRegistry.class.getName());

    private static final Pattern COMMAND_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Set<String> COMMAND_KEYS =
            Set.of(
                    "description",
                    "exec",
                    "shell",
                    "command",
                    "rung",
                    "pipeline",
                    "timeout",
                    "env",
                    "tool",
                    "sandbox",
                    "unattended",
                    "approval");

    /// The only value `pipeline:` accepts.
    ///
    /// It is an acknowledgement rather than a setting: `/bin/sh` is `dash` on
    /// Debian and Ubuntu, `set -o pipefail` is not POSIX before Issue 8, and dash
    /// treats it as a special-builtin error that exits the shell outright – so
    /// there is no switch to flip, only an author who has to know what status
    /// their entry will report.
    private static final String PIPELINE_ACKNOWLEDGEMENT = "last-stage-status";
    private static final Set<String> PARAM_KEYS =
            Set.of("type", "required", "pattern", "enum", "maxLength", "secret");

    private final Map<String, CommandDefinition> commands;

    /// Creates an empty catalog.
    public CommandRegistry() {
        this.commands = new HashMap<>();
    }

    /// Creates a catalog over already-compiled definitions.
    ///
    /// @param commands definitions keyed by command id, not null
    public CommandRegistry(Map<String, CommandDefinition> commands) {
        this.commands = new HashMap<>(commands);
    }

    /// Loads a catalog from a file, treating the file's own directory as the
    /// working directory every relative path is resolved against.
    ///
    /// @param path the `commands.yaml` to read, not null
    /// @return the compiled catalog, never null (empty when the file is absent)
    /// @throws IOException if the file exists but cannot be read
    /// @throws CommandConfigException if the catalog breaks a grammar or safety rule
    public static CommandRegistry loadFromFile(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        return loadFromFile(path, parent != null ? parent : Path.of("").toAbsolutePath());
    }

    /// Loads a catalog from a file against an explicit working directory.
    ///
    /// An absent file is not an error: a deployment that defines no commands has
    /// an empty allowlist, which is the safe reading.
    ///
    /// @param path the `commands.yaml` to read, not null
    /// @param workingDirectory the directory relative paths resolve against, not null
    /// @return the compiled catalog, never null (empty when the file is absent)
    /// @throws IOException if the file exists but cannot be read
    /// @throws CommandConfigException if the catalog breaks a grammar or safety rule
    public static CommandRegistry loadFromFile(Path path, Path workingDirectory)
            throws IOException {
        if (!Files.exists(path)) {
            logger.warning("Commands file not found: " + path + ". Using empty registry.");
            return new CommandRegistry();
        }
        return parse(Files.readString(path), workingDirectory);
    }

    /// Compiles a catalog from document text.
    ///
    /// The `PATH` used to resolve executables is captured once here, so the
    /// binary behind a command is pinned for the life of the catalog and a later
    /// environment change cannot swap it.
    ///
    /// @param content the `commands.yaml` document, not null
    /// @param workingDirectory the directory relative paths resolve against, not null
    /// @return the compiled catalog, never null
    /// @throws CommandConfigException if the catalog breaks a grammar or safety rule
    public static CommandRegistry parse(String content, Path workingDirectory) {
        Path workingDir = workingDirectory.toAbsolutePath().normalize();
        List<Path> searchPath = searchPath();
        MiniYaml.Mapping root;
        try {
            root = MiniYaml.parse(content);
        } catch (MiniYamlException e) {
            throw new CommandConfigException(stripLinePrefix(e), e.line(), e);
        }

        Map<String, CommandDefinition> compiled = new LinkedHashMap<>();
        try {
            if (!root.has("commands")) {
                logger.warning(
                        "Commands file declares no 'commands:' block. Using empty registry.");
                return new CommandRegistry();
            }
            MiniYaml.Mapping block = root.require("commands").asMapping();
            for (String id : block.keys()) {
                MiniYaml.Value entry = block.get(id);
                if (!COMMAND_ID.matcher(id).matches()) {
                    throw new CommandConfigException(
                            "command id '" + id + "' must match " + COMMAND_ID.pattern(),
                            entry.line());
                }
                compiled.put(id, compile(id, entry.asMapping(), workingDir, searchPath));
            }
        } catch (MiniYamlException e) {
            throw new CommandConfigException(stripLinePrefix(e), e.line(), e);
        }

        logger.info("Loaded " + compiled.size() + " commands from registry");
        return new CommandRegistry(compiled);
    }

    // --------------------------------------------------------------- compiling

    private static CommandDefinition compile(
            String id, MiniYaml.Mapping entry, Path workingDir, List<Path> searchPath) {
        rejectUnknownKeys(id, entry, COMMAND_KEYS, "command");
        requireDescription(id, entry);

        boolean rungMode = entry.has("rung") && entry.require("rung").asBoolean();
        boolean shellMode = entry.has("shell") && entry.require("shell").asBoolean();
        if (rungMode) {
            rejectRungElevation(id, entry, shellMode);
        }

        ToolSpec toolSpec =
                rungMode
                        ? rungToolSpec(id, entry)
                        : entry.has("tool")
                                ? toolSpec(id, entry.require("tool").asMapping())
                                : null;
        SandboxPolicy sandbox =
                entry.has("sandbox")
                        ? sandboxPolicy(id, entry.require("sandbox").asMapping(), workingDir)
                        : SandboxPolicy.restrictive();
        Map<String, String> environment = environment(id, entry);
        long timeout =
                entry.has("timeout")
                        ? entry.require("timeout").asLong()
                        : CommandDefinition.DEFAULT_TIMEOUT_MS;
        if (timeout <= 0) {
            throw new CommandConfigException(
                    "command '" + id + "' declares a non-positive timeout",
                    entry.require("timeout").line());
        }

        List<String> execTemplate = null;
        String shellCommand = null;
        if (rungMode) {
            requireNoPipelineKey(id, entry, "a rung");
        } else if (shellMode) {
            shellCommand = compileShell(id, entry, toolSpec);
        } else {
            requireNoPipelineKey(id, entry, "argv form");
            execTemplate = compileExec(id, entry, toolSpec, workingDir, searchPath);
        }

        return new CommandDefinition(
                execTemplate,
                shellCommand,
                timeout,
                environment,
                toolSpec,
                sandbox,
                !entry.has("unattended") || entry.require("unattended").asBoolean(),
                approvalRequired(id, entry),
                rungMode);
    }

    // ------------------------------------------------------------------- rung

    /// Refuses every key that would widen a rung beyond the closed default.
    ///
    /// The rung is the one entry form whose command text an agent writes, so the
    /// only thing standing between it and the host is the sandbox. Merging an
    /// entry's `sandbox:` or `env:` into it would make that floor configurable,
    /// and a floor a configuration file can lower is not a floor. Elevation stays
    /// reachable only through named entries whose command text a human wrote.
    ///
    /// @param id the command id being compiled, not null
    /// @param entry the command's mapping, not null
    /// @param shellMode whether the entry also declared `shell: true`
    /// @throws CommandConfigException naming the offending key and its line
    private static void rejectRungElevation(String id, MiniYaml.Mapping entry, boolean shellMode) {
        if (shellMode) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares both rung: true and shell: true; a command takes"
                            + " exactly one form",
                    entry.require("shell").line());
        }
        for (String key : List.of("exec", "command")) {
            if (entry.has(key)) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' declares rung: true with "
                                + key
                                + ":; a rung's command line comes from the agent, so the entry"
                                + " carries no command text of its own",
                        entry.require(key).line());
            }
        }
        if (entry.has("sandbox")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares rung: true with a sandbox: block; a rung runs under the"
                            + " fixed closed policy – no network:, no cache:, and no write: beyond"
                            + " the working directory and the call's private home – which cannot"
                            + " be widened here",
                    entry.require("sandbox").line());
        }
        if (entry.has("env")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares rung: true with env:; a rung receives no extra"
                            + " environment",
                    entry.require("env").line());
        }
    }

    /// Builds the fixed schema a rung is exposed to agents through.
    ///
    /// A rung that no agent can call is dead configuration carrying a live
    /// capability, so the `tool:` block is required rather than optional. Its
    /// `params:` are not the author's to write: the one free-text parameter in the
    /// catalog is the one the policy floor is pinned around.
    private static ToolSpec rungToolSpec(String id, MiniYaml.Mapping entry) {
        if (!entry.has("tool")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares rung: true without a tool: block; a rung exists so an"
                            + " agent can call it",
                    entry.line());
        }
        MiniYaml.Mapping tool = entry.require("tool").asMapping();
        if (tool.has("params")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares rung: true with tool: params:; a rung takes one fixed"
                            + " parameter '"
                            + CommandDefinition.RUNG_PARAM
                            + "' carrying the command line",
                    tool.get("params").line());
        }
        rejectUnknownKeys(id, tool, Set.of("description"), "tool block of rung command");
        return CommandDefinition.rungToolSpec(tool.require("description").asString());
    }

    private static void requireNoPipelineKey(String id, MiniYaml.Mapping entry, String what) {
        if (entry.has("pipeline")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares pipeline: on "
                            + what
                            + "; only shell: true text runs a pipeline whose status needs"
                            + " acknowledging",
                    entry.require("pipeline").line());
        }
    }

    private static List<String> compileExec(
            String id,
            MiniYaml.Mapping entry,
            ToolSpec toolSpec,
            Path workingDir,
            List<Path> searchPath) {
        if (entry.has("command")) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' uses the removed single-string command: form; declare"
                            + " exec: [\"binary\", \"arg\"] or shell: true with command:",
                    entry.require("command").line());
        }
        if (!entry.has("exec")) {
            throw new CommandConfigException(
                    "command '" + id + "' declares neither exec: nor shell: true with command:",
                    entry.line());
        }
        MiniYaml.Value execValue = entry.require("exec");
        List<String> template = execValue.asStringList();
        if (template.isEmpty()) {
            throw new CommandConfigException(
                    "command '" + id + "' declares an empty exec: list", execValue.line());
        }

        int line = execValue.line();
        for (int i = 0; i < template.size(); i++) {
            String element = template.get(i);
            Matcher matcher = CommandDefinition.PLACEHOLDER.matcher(element);
            List<String> referenced = new ArrayList<>();
            while (matcher.find()) {
                referenced.add(matcher.group(1));
            }
            if (referenced.isEmpty()) {
                continue;
            }
            if (i == 0) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' places a placeholder in the executable position; the binary"
                                + " must be a literal",
                        line);
            }
            if (referenced.size() > 1) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' element '"
                                + element
                                + "' carries "
                                + referenced.size()
                                + " placeholders; an element may carry at most one",
                        line);
            }
            String name = referenced.getFirst();
            if (toolSpec == null || !toolSpec.declares(name)) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' references undeclared parameter '"
                                + name
                                + "'; declare it under tool: params:",
                        line);
            }
            if (toolSpec.param(name).orElseThrow().isList() && !element.equals("{" + name + "}")) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' embeds list parameter '"
                                + name
                                + "' in '"
                                + element
                                + "'; a list parameter must occupy its element alone",
                        line);
            }
        }

        List<String> compiled = new ArrayList<>(template);
        compiled.set(0, resolveExecutable(id, template.getFirst(), workingDir, searchPath, line));
        return compiled;
    }

    private static String compileShell(String id, MiniYaml.Mapping entry, ToolSpec toolSpec) {
        if (entry.has("exec")) {
            throw new CommandConfigException(
                    "command '" + id + "' declares both exec: and shell: true",
                    entry.require("exec").line());
        }
        if (!entry.has("command")) {
            throw new CommandConfigException(
                    "command '" + id + "' declares shell: true without command:", entry.line());
        }
        MiniYaml.Value value = entry.require("command");
        String text = value.asString();
        Matcher matcher = CommandDefinition.PLACEHOLDER.matcher(text);
        if (matcher.find()) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' splices placeholder '{"
                            + matcher.group(1)
                            + "}' into shell text; shell-mode parameters arrive as "
                            + ParamSpec.ENV_PREFIX
                            + "* environment variables only",
                    value.line());
        }
        requirePipelineAcknowledgement(id, entry, text, value.line());
        if (toolSpec != null) {
            Map<String, String> mapped = new HashMap<>();
            for (ParamSpec param : toolSpec.params()) {
                String variable = param.environmentVariable();
                String previous = mapped.put(variable, param.name());
                if (previous != null) {
                    throw new CommandConfigException(
                            "command '"
                                    + id
                                    + "' maps parameters '"
                                    + previous
                                    + "' and '"
                                    + param.name()
                                    + "' onto the same variable "
                                    + variable,
                            value.line());
                }
            }
        }
        return text;
    }

    /// Makes an author say out loud what status a pipeline will report.
    ///
    /// `/bin/sh -c 'false | tail -1'` exits 0, because a pipeline reports its last
    /// stage. An entry built that way turns a failed build into a success, and an
    /// `onSuccess` arm then routes a broken run forward – the one failure an
    /// unattended run cannot recover from. There is no `pipefail` to reach for:
    /// `/bin/sh` is `dash` on Debian and Ubuntu, where `set -o pipefail` is a
    /// special-builtin error that kills the shell, and launching `/bin/bash`
    /// instead would trade a correctness bug for a host dependency.
    ///
    /// The check is a plain character scan and over-approximates on purpose – a
    /// `|` inside a quoted string trips it too. The remedy is one key in a
    /// human-authored file; the alternative is a shell lexer in `hensu-core`.
    ///
    /// @param id the command id being compiled, not null
    /// @param entry the command's mapping, not null
    /// @param text the shell text as written, not null
    /// @param line the line `command:` sits on
    /// @throws CommandConfigException if the text pipes without the acknowledgement
    private static void requirePipelineAcknowledgement(
            String id, MiniYaml.Mapping entry, String text, int line) {
        boolean acknowledged = false;
        if (entry.has("pipeline")) {
            MiniYaml.Value declared = entry.require("pipeline");
            if (!PIPELINE_ACKNOWLEDGEMENT.equals(declared.asString())) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' declares pipeline: '"
                                + declared.asString()
                                + "', expected "
                                + PIPELINE_ACKNOWLEDGEMENT,
                        declared.line());
            }
            acknowledged = true;
        }
        if (text.indexOf('|') >= 0 && !acknowledged) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' pipes in shell text without declaring pipeline: "
                            + PIPELINE_ACKNOWLEDGEMENT
                            + "; a pipeline reports its last stage's status, so a failing first"
                            + " stage would be routed as success",
                    line);
        }
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
                    throw new CommandConfigException(
                            "command '"
                                    + id
                                    + "' declares approval: '"
                                    + value.asString()
                                    + "', expected required or none",
                            value.line());
        };
    }

    private static Map<String, String> environment(String id, MiniYaml.Mapping entry) {
        if (!entry.has("env")) {
            return Map.of();
        }
        MiniYaml.Mapping env = entry.require("env").asMapping();
        Map<String, String> variables = new LinkedHashMap<>();
        for (String key : env.keys()) {
            if (key.startsWith(ParamSpec.ENV_PREFIX)) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' declares env: key '"
                                + key
                                + "'; the "
                                + ParamSpec.ENV_PREFIX
                                + " namespace is reserved for parameters",
                        env.get(key).line());
            }
            variables.put(key, env.get(key).asString());
        }
        return variables;
    }

    private static ToolSpec toolSpec(String id, MiniYaml.Mapping tool) {
        rejectUnknownKeys(id, tool, Set.of("description", "params"), "tool block of command");
        String description = tool.require("description").asString();
        List<ParamSpec> params = new ArrayList<>();
        if (tool.has("params")) {
            MiniYaml.Mapping declared = tool.require("params").asMapping();
            for (String name : declared.keys()) {
                params.add(paramSpec(id, name, declared.get(name)));
            }
        }
        return new ToolSpec(description, params);
    }

    private static ParamSpec paramSpec(String id, String name, MiniYaml.Value value) {
        MiniYaml.Mapping schema = value.asMapping();
        for (String key : schema.keys()) {
            if (!PARAM_KEYS.contains(key)) {
                throw new CommandConfigException(
                        "parameter '"
                                + name
                                + "' of command '"
                                + id
                                + "' declares unknown key '"
                                + key
                                + "', expected one of "
                                + PARAM_KEYS,
                        schema.get(key).line());
            }
        }
        String type = schema.has("type") ? schema.require("type").asString() : "string";
        if (!ParamSpec.TYPES.contains(type)) {
            throw new CommandConfigException(
                    "parameter '"
                            + name
                            + "' of command '"
                            + id
                            + "' declares unknown type '"
                            + type
                            + "', expected one of "
                            + ParamSpec.TYPES,
                    schema.line());
        }
        String pattern = schema.has("pattern") ? schema.require("pattern").asString() : null;
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new CommandConfigException(
                        "parameter '"
                                + name
                                + "' of command '"
                                + id
                                + "' declares an uncompilable pattern: "
                                + e.getDescription(),
                        schema.require("pattern").line(),
                        e);
            }
        }
        List<String> enumValues =
                schema.has("enum") ? schema.require("enum").asStringList() : List.of();
        Integer maxLength =
                schema.has("maxLength") ? (int) schema.require("maxLength").asLong() : null;
        return new ParamSpec(
                name,
                type,
                schema.has("required") && schema.require("required").asBoolean(),
                pattern,
                enumValues,
                maxLength,
                schema.has("secret") && schema.require("secret").asBoolean());
    }

    private static SandboxPolicy sandboxPolicy(
            String id, MiniYaml.Mapping sandbox, Path workingDir) {
        rejectUnknownKeys(
                id, sandbox, Set.of("network", "write", "cache"), "sandbox block of command");
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
                cachePaths.add(validateCachePath(id, entry, workingDir, value.line()));
            }
        }
        return new SandboxPolicy(network, writePaths, cachePaths);
    }

    private static String validateWritePath(String id, String entry, Path workingDir, int line) {
        Path resolved = resolve(id, entry, workingDir, line);
        if (!resolved.startsWith(workingDir)) {
            throw new CommandConfigException(
                    "command '"
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

    private static String validateCachePath(String id, String entry, Path workingDir, int line) {
        String expanded = expandTilde(entry);
        if (!Path.of(expanded).isAbsolute()) {
            throw new CommandConfigException(
                    "command '" + id + "' declares cache: '" + entry + "' which is not absolute",
                    line);
        }
        Path resolved = resolve(id, expanded, workingDir, line);
        if (resolved.startsWith(workingDir)) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares cache: '"
                            + entry
                            + "' inside the working directory; use write: for project paths",
                    line);
        }
        if (resolved.getParent() == null) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares cache: '"
                            + entry
                            + "' which is a filesystem root; name a cache subtree instead",
                    line);
        }
        String home = System.getProperty("user.home");
        if (home != null
                && !home.isBlank()
                && resolved.equals(Path.of(home).toAbsolutePath().normalize())) {
            throw new CommandConfigException(
                    "command '"
                            + id
                            + "' declares cache: '"
                            + entry
                            + "' which is the home directory itself; name a cache subtree such as"
                            + " ~/.gradle/caches instead",
                    line);
        }
        return resolved.toString();
    }

    private static Path resolve(String id, String entry, Path workingDir, int line) {
        try {
            return workingDir.resolve(entry).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new CommandConfigException(
                    "command '" + id + "' declares the unusable path '" + entry + "'", line, e);
        }
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

    private static String resolveExecutable(
            String id, String binary, Path workingDir, List<Path> searchPath, int line) {
        if (binary.contains("/")) {
            Path resolved = resolve(id, binary, workingDir, line);
            if (!Files.isExecutable(resolved)) {
                throw new CommandConfigException(
                        "command '"
                                + id
                                + "' names executable '"
                                + binary
                                + "' which resolves to "
                                + resolved
                                + " and is not an executable file",
                        line);
            }
            return resolved.toString();
        }
        for (Path directory : searchPath) {
            Path candidate = directory.resolve(binary);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new CommandConfigException(
                "command '" + id + "' names executable '" + binary + "' which is not on PATH",
                line);
    }

    private static List<Path> searchPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        for (String element : path.split(java.io.File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            try {
                directories.add(Path.of(element));
            } catch (InvalidPathException e) {
                logger.warning("Ignoring unusable PATH element: " + element);
            }
        }
        return directories;
    }

    /// Requires the entry to state, for a human reader, why it is in the catalog.
    ///
    /// An allowlist only stays an allowlist while somebody can still tell which
    /// entries are needed. Without a stated purpose an entry that has outlived its
    /// caller is indistinguishable from one that has not, so nobody dares remove it
    /// and the catalog grows into a permanent grant of everything it ever held. The
    /// text is for reviewers rather than for models – {@link ToolSpec#description}
    /// is the separate, agent-facing sentence, and a command may need both.
    ///
    /// @param id the command id being compiled, not null
    /// @param entry the command's mapping, not null
    /// @throws CommandConfigException if `description:` is absent or blank
    private static void requireDescription(String id, MiniYaml.Mapping entry) {
        if (!entry.has("description")) {
            throw new CommandConfigException(
                    "command '" + id + "' must declare a description explaining why it exists",
                    entry.line());
        }
        MiniYaml.Value value = entry.require("description");
        if (value.asString().isBlank()) {
            throw new CommandConfigException(
                    "command '" + id + "' declares a blank description", value.line());
        }
    }

    private static void rejectUnknownKeys(
            String id, MiniYaml.Mapping mapping, Set<String> permitted, String what) {
        for (String key : mapping.keys()) {
            if (!permitted.contains(key)) {
                throw new CommandConfigException(
                        what
                                + " '"
                                + id
                                + "' declares unknown key '"
                                + key
                                + "', expected one of "
                                + permitted,
                        mapping.get(key).line());
            }
        }
    }

    private static String stripLinePrefix(MiniYamlException e) {
        String message = e.getMessage();
        String prefix = "line " + e.line() + ": ";
        return message != null && message.startsWith(prefix)
                ? message.substring(prefix.length())
                : message;
    }

    // ------------------------------------------------------------------ lookup

    /// Returns a compiled command by id.
    ///
    /// @param commandId the catalog id, not null
    /// @return the compiled definition, never null
    /// @throws IllegalArgumentException if no command carries this id
    public CommandDefinition getCommand(String commandId) {
        CommandDefinition def = commands.get(commandId);
        if (def == null) {
            throw new IllegalArgumentException(
                    "Command not found in registry: '"
                            + commandId
                            + "'. Available commands: "
                            + commands.keySet());
        }
        return def;
    }

    /// Returns whether the catalog carries a command under this id.
    ///
    /// @param commandId the catalog id, not null
    /// @return true if the command exists
    public boolean hasCommand(String commandId) {
        return commands.containsKey(commandId);
    }

    /// Returns every command id in the catalog.
    ///
    /// @return an unmodifiable view of the ids, never null
    public Set<String> getCommandIds() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    /// Returns the commands that opted into agent visibility with a `tool:` block.
    ///
    /// @return an unmodifiable map of agent-callable definitions by id, never null
    public Map<String, CommandDefinition> agentVisibleCommands() {
        Map<String, CommandDefinition> visible = new LinkedHashMap<>();
        commands.forEach(
                (id, definition) -> {
                    if (definition.agentVisible()) {
                        visible.put(id, definition);
                    }
                });
        return Collections.unmodifiableMap(visible);
    }

    /// Adds a command to the catalog programmatically.
    ///
    /// @apiNote **Side effects**: overwrites any command already registered under
    /// this id. Intended for tests and embedders that build a catalog in code;
    /// file-loaded catalogs go through the load-time validation instead.
    ///
    /// @param id the catalog id, not null
    /// @param definition the compiled definition, not null
    public void registerCommand(String id, CommandDefinition definition) {
        commands.put(id, definition);
    }
}
