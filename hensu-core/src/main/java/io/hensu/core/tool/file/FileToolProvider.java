package io.hensu.core.tool.file;

import io.hensu.core.tool.BuiltInToolProvider;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import io.hensu.core.tool.ToolPreview;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Reading and editing files as an engine capability rather than a command.
///
/// A run that cannot read the input it is meant to act on is not constrained,
/// it is non-functional, and making that capability a deployment concern means
/// every deployment reinvents it. These six tools need no binary, no sandbox
/// launch and no configuration: they are present wherever the engine is, rooted
/// by construction at the directory the run was given.
///
/// | Tool | Shape |
/// |------|-------|
/// | `read_file` | path, optional line offset and limit |
/// | `list_dir` | path |
/// | `glob` | pattern, rooted at the confinement root |
/// | `grep` | regex, optional path prefix and name filter |
/// | `write_file` | path, full content |
/// | `edit_file` | path, exact `old` text, `new` text – unique match or fail |
///
/// ### They run outside the sandbox
/// Nothing is launched, so nothing contains them. Confinement is this class's
/// own responsibility and lives in {@link PathGuard}, which walks a path one
/// segment at a time rather than trusting a resolved string. The same reasoning
/// makes the catalog off limits: `commands.yaml` and `mcp.yaml` decide what a
/// deployment may execute, the sandbox launchers re-mask them read-only, and a
/// file tool that could rewrite them would hand back exactly the capability that
/// masking exists to remove.
///
/// ### Budgets are part of the contract
/// Every tool caps its own output and says so when it truncates. An untruncated
/// search over a large tree is a context-window denial of service against the
/// agent that asked for it, so a bounded answer with a visible marker is the
/// useful answer, not a degraded one.
///
/// ### Precedence
/// The provider is a {@link BuiltInToolProvider}: it is always present and
/// nobody opted into it, so a name it shares with a configured filesystem MCP
/// server yields to that server instead of failing startup.
///
/// ### Contracts
/// - **Precondition**: the root exists and is a directory
/// - **Postcondition**: no tool reads or writes outside the root
/// - **Invariant**: `commands.yaml` and `mcp.yaml` are never written
///
/// @implNote **Immutable after construction.** Holds only its guard; every call
/// is independent, so the provider is safe to share across Virtual Threads.
/// @see PathGuard for the containment walk
/// @see io.hensu.core.execution.action.ProtectedConfigFiles for the write deny list
public final class FileToolProvider implements BuiltInToolProvider, PreviewCapable {

    /// Name of the tool that reads a file, whole or by line range.
    public static final String READ_FILE = "read_file";

    /// Name of the tool that lists one directory.
    public static final String LIST_DIR = "list_dir";

    /// Name of the tool that matches paths against a glob pattern.
    public static final String GLOB = "glob";

    /// Name of the tool that searches file contents for a regular expression.
    public static final String GREP = "grep";

    /// Name of the tool that writes a file in full.
    public static final String WRITE_FILE = "write_file";

    /// Name of the tool that replaces one exact passage inside a file.
    public static final String EDIT_FILE = "edit_file";

    /// Characters {@link #READ_FILE} returns before it truncates.
    public static final int MAX_READ_CHARS = 64 * 1024;

    /// Lines {@link #READ_FILE} returns when the call names no limit.
    public static final int DEFAULT_READ_LINES = 2_000;

    /// Entries {@link #LIST_DIR} returns before it truncates.
    public static final int MAX_ENTRIES = 500;

    /// Results {@link #GLOB} and {@link #GREP} return before they truncate.
    public static final int MAX_MATCHES = 200;

    /// Characters {@link #WRITE_FILE} and {@link #EDIT_FILE} accept as content.
    public static final int MAX_WRITE_CHARS = 1024 * 1024;

    /// Characters of a single line {@link #GREP} reports before shortening it.
    public static final int MAX_MATCH_LINE_CHARS = 500;

    /// Wall clock a single call may spend before it reports
    /// {@link ToolCallStatus#TIMEOUT}.
    ///
    /// {@link io.hensu.core.tool.ToolProvider} requires every provider to bound
    /// its own execution because the engine runs no watchdog. A search over a
    /// pathological tree is the only tool here that can take real time, but the
    /// deadline covers all six so the obligation is met in one place.
    public static final long CALL_TIMEOUT_MS = 15_000L;

    private static final int BINARY_SNIFF_BYTES = 8_000;
    private static final String TRUNCATED = "\n[truncated: ";

    private final PathGuard guard;

    /// Creates a provider confined to one directory.
    ///
    /// @param root the directory every tool is restricted to, not null
    /// @throws FileToolException if the root does not exist or is not a directory
    /// @throws NullPointerException if root is null
    public FileToolProvider(Path root) {
        this.guard = new PathGuard(Objects.requireNonNull(root, "root must not be null"));
    }

    /// Returns the directory the tools are confined to.
    ///
    /// @return the resolved root, never null
    public Path root() {
        return guard.root();
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                ToolDefinition.of(
                        READ_FILE,
                        "Read a UTF-8 text file inside the working directory. Returns the whole"
                                + " file, or the line range named by offset and limit.",
                        List.of(
                                ParameterDef.required(
                                        "path", "string", "Path relative to the working directory"),
                                ParameterDef.optional(
                                        "offset", "number", "First line to return, 1-based", null),
                                ParameterDef.optional(
                                        "limit", "number", "How many lines to return", null))),
                ToolDefinition.of(
                        LIST_DIR,
                        "List the entries of one directory inside the working directory."
                                + " Directories are suffixed with a slash.",
                        List.of(
                                ParameterDef.optional(
                                        "path",
                                        "string",
                                        "Directory relative to the working directory",
                                        "."))),
                ToolDefinition.of(
                        GLOB,
                        "Find files whose path matches a glob pattern, for example"
                                + " src/**/*.java. Patterns are matched against paths relative to"
                                + " the working directory.",
                        List.of(ParameterDef.required("pattern", "string", "Glob pattern"))),
                ToolDefinition.of(
                        GREP,
                        "Search file contents for a Java regular expression. Reports matching"
                                + " lines as path:line:text. Binary files are skipped.",
                        List.of(
                                ParameterDef.required("pattern", "string", "Regular expression"),
                                ParameterDef.optional(
                                        "path",
                                        "string",
                                        "Directory or file to search, relative to the working"
                                                + " directory",
                                        "."),
                                ParameterDef.optional(
                                        "glob",
                                        "string",
                                        "Only search files whose relative path matches this glob",
                                        null))),
                ToolDefinition.of(
                        WRITE_FILE,
                        "Write a file in full, creating it and any missing parent directories."
                                + " Replaces existing content.",
                        List.of(
                                ParameterDef.required(
                                        "path", "string", "Path relative to the working directory"),
                                ParameterDef.required(
                                        "content", "string", "The complete content"))),
                ToolDefinition.of(
                        EDIT_FILE,
                        "Replace one exact passage in a file. The old text must appear exactly"
                                + " once, otherwise the edit is refused rather than guessed.",
                        List.of(
                                ParameterDef.required(
                                        "path", "string", "Path relative to the working directory"),
                                ParameterDef.required(
                                        "old", "string", "Text to replace, matched literally"),
                                ParameterDef.required("new", "string", "Replacement text"))));
    }

    @Override
    public boolean provides(String toolName) {
        return switch (toolName) {
            case READ_FILE, LIST_DIR, GLOB, GREP, WRITE_FILE, EDIT_FILE -> true;
            default -> false;
        };
    }

    /// Describes a file-tool call without performing it.
    ///
    /// File tools launch no process, so there is no argv to show and the
    /// description is the operation and its target. They are always safe to run
    /// unattended: confinement is structural rather than a policy a reviewer
    /// would be asked to waive.
    ///
    /// @param toolName the tool identifier to describe, not null
    /// @param arguments arguments the agent supplied, not null (may be empty)
    /// @return the description of the call, never null
    /// @throws IllegalArgumentException if this provider does not offer the named tool
    @Override
    public ToolPreview preview(String toolName, Map<String, Object> arguments) {
        if (!provides(toolName)) {
            throw new IllegalArgumentException("Not a built-in file tool: " + toolName);
        }
        Object target = arguments.getOrDefault("path", arguments.get("pattern"));
        return ToolPreview.describing(target != null ? toolName + " " + target : toolName, true);
    }

    /// Runs a file tool inside the confinement root.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null and unused – file
    ///     tools are rooted at construction, never at call time
    /// @return the invocation result, never null
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        Deadline deadline = Deadline.starting();
        try {
            return switch (toolName) {
                case READ_FILE -> readFile(arguments);
                case LIST_DIR -> listDir(arguments);
                case GLOB -> glob(arguments, deadline);
                case GREP -> grep(arguments, deadline);
                case WRITE_FILE -> writeFile(arguments);
                case EDIT_FILE -> editFile(arguments);
                default ->
                        ToolCallResult.of(
                                toolName,
                                ToolCallStatus.UNKNOWN_TOOL,
                                null,
                                "'" + toolName + "' is not a built-in file tool",
                                null);
            };
        } catch (FileToolException e) {
            return ToolCallResult.of(toolName, e.status(), null, e.getMessage(), null);
        } catch (IOException e) {
            return ToolCallResult.failure(toolName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------- tools

    private ToolCallResult readFile(Map<String, Object> args) throws IOException {
        Path target = guard.resolve(string(args, "path", true));
        int offset = Math.max(1, (int) number(args, "offset", 1));
        int limit = (int) number(args, "limit", DEFAULT_READ_LINES);
        if (limit <= 0) {
            throw FileToolException.invalid("limit must be positive, got " + limit);
        }

        StringBuilder text = new StringBuilder();
        int returned = 0;
        int lineNumber = 0;
        boolean truncated = false;
        boolean lineCut = false;
        try (InputStream stream = guard.openForRead(target);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) {
                    continue;
                }
                if (returned == limit) {
                    truncated = true;
                    break;
                }
                if (text.length() + line.length() > MAX_READ_CHARS) {
                    // A first line wider than the whole budget has to yield
                    // something: returning the note alone would repeat the
                    // caller's own offset, and a caller that follows the hint
                    // would ask for the same line forever.
                    if (returned == 0) {
                        text.append(line, 0, MAX_READ_CHARS).append('\n');
                        returned++;
                        lineCut = true;
                    }
                    truncated = true;
                    break;
                }
                text.append(line).append('\n');
                returned++;
            }
        }
        if (truncated) {
            text.append(TRUNCATED)
                    .append(
                            lineCut
                                    ? "line "
                                            + offset
                                            + " is longer than the character budget and was cut"
                                    : "read stopped after " + returned + " lines")
                    .append("; continue with offset ")
                    .append(offset + returned)
                    .append(']');
        }
        return ToolCallResult.success(READ_FILE, text.toString());
    }

    private ToolCallResult listDir(Map<String, Object> args) throws IOException {
        Path target = guard.resolve(string(args, "path", false, "."));
        if (!Files.isDirectory(target)) {
            throw FileToolException.invalid("not a directory: " + guard.relative(target));
        }

        List<String> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path entry : stream) {
                if (entries.size() == MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(describe(entry));
            }
        } catch (NoSuchFileException e) {
            throw FileToolException.invalid("no such directory: " + guard.relative(target));
        }
        entries.sort(String::compareTo);
        return ToolCallResult.success(
                LIST_DIR, join(entries, truncated, "more than " + MAX_ENTRIES + " entries"));
    }

    private ToolCallResult glob(Map<String, Object> args, Deadline deadline) throws IOException {
        PathMatcher matcher = matcher(string(args, "pattern", true));
        List<String> matches = new ArrayList<>();
        walk(
                guard.root(),
                deadline,
                (relative, attributes) -> {
                    if (attributes.isRegularFile() && matcher.matches(relative)) {
                        matches.add(relative.toString());
                    }
                    return matches.size() <= MAX_MATCHES;
                });
        matches.sort(String::compareTo);
        return ToolCallResult.success(
                GLOB, join(trim(matches), matches.size() > MAX_MATCHES, budgetNote()));
    }

    private ToolCallResult grep(Map<String, Object> args, Deadline deadline) throws IOException {
        Pattern pattern = regex(string(args, "pattern", true));
        Path scope = guard.resolve(string(args, "path", false, "."));
        String globArgument = string(args, "glob", false, null);
        PathMatcher filter = globArgument != null ? matcher(globArgument) : null;

        List<String> matches = new ArrayList<>();
        if (Files.isDirectory(scope)) {
            walk(
                    scope,
                    deadline,
                    (relative, attributes) -> {
                        if (attributes.isRegularFile()
                                && (filter == null || filter.matches(relative))) {
                            search(guard.root().resolve(relative), relative, pattern, matches);
                        }
                        return matches.size() <= MAX_MATCHES;
                    });
        } else {
            search(scope, guard.root().relativize(scope), pattern, matches);
        }
        return ToolCallResult.success(
                GREP, join(trim(matches), matches.size() > MAX_MATCHES, budgetNote()));
    }

    private ToolCallResult writeFile(Map<String, Object> args) throws IOException {
        Path target = guard.resolve(string(args, "path", true));
        String content = content(args, "content");
        guard.requireWritable(target);

        Path parent = target.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = guard.openForWrite(target, true)) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return ToolCallResult.success(
                WRITE_FILE,
                "Wrote " + content.length() + " characters to " + guard.relative(target));
    }

    private ToolCallResult editFile(Map<String, Object> args) throws IOException {
        Path target = guard.resolve(string(args, "path", true));
        String oldText = string(args, "old", true);
        String newText = content(args, "new");
        guard.requireWritable(target);

        String current = read(target);
        int first = current.indexOf(oldText);
        if (first < 0) {
            throw FileToolException.invalid(
                    "the old text does not appear in "
                            + guard.relative(target)
                            + "; read the file and copy the passage exactly");
        }
        if (current.indexOf(oldText, first + oldText.length()) >= 0) {
            throw FileToolException.invalid(
                    "the old text appears more than once in "
                            + guard.relative(target)
                            + "; extend it until it identifies one passage");
        }
        String updated =
                current.substring(0, first) + newText + current.substring(first + oldText.length());
        if (updated.length() > MAX_WRITE_CHARS) {
            throw FileToolException.invalid(
                    "the edit would grow "
                            + guard.relative(target)
                            + " past "
                            + MAX_WRITE_CHARS
                            + " characters");
        }
        try (OutputStream stream = guard.openForWrite(target, false)) {
            stream.write(updated.getBytes(StandardCharsets.UTF_8));
        }
        return ToolCallResult.success(
                EDIT_FILE, "Replaced one passage in " + guard.relative(target));
    }

    // ------------------------------------------------------------------ search

    /// Visits regular files under a directory without following symbolic links.
    ///
    /// Links are not followed and not visited: following one would leave the
    /// root through a path the guard never checked, and reporting one invites
    /// the agent to read it by name, which the guard then refuses anyway.
    ///
    /// @param start the directory to walk, not null
    /// @param deadline the wall clock for this call, not null
    /// @param visitor receives each regular file, not null
    /// @throws IOException if the tree cannot be walked at all
    /// @throws FileToolException if the call outlives {@link #CALL_TIMEOUT_MS}
    private void walk(Path start, Deadline deadline, Visitor visitor) throws IOException {
        Files.walkFileTree(
                start,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (deadline.expired()) {
                            throw new FileToolException(
                                    ToolCallStatus.TIMEOUT,
                                    "search exceeded "
                                            + CALL_TIMEOUT_MS
                                            + "ms; narrow it with a"
                                            + " path or a glob");
                        }
                        if (attributes.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE;
                        }
                        return visitor.visit(guard.root().relativize(file), attributes)
                                ? FileVisitResult.CONTINUE
                                : FileVisitResult.TERMINATE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /// Appends every matching line of one file, skipping binary content.
    private void search(Path file, Path relative, Pattern pattern, List<String> matches) {
        try (InputStream stream = guard.openForRead(file)) {
            byte[] head = stream.readNBytes(BINARY_SNIFF_BYTES);
            if (isBinary(head)) {
                return;
            }
            // Decoded once, over the sniffed bytes and the rest together: two
            // decodes would split a character sitting on the sniffer's boundary
            // and hand the pattern a replacement character in its place.
            ByteArrayOutputStream body =
                    new ByteArrayOutputStream(head.length + BINARY_SNIFF_BYTES);
            body.write(head, 0, head.length);
            stream.transferTo(body);
            String text = body.toString(StandardCharsets.UTF_8);
            int lineNumber = 0;
            for (String line : text.split("\n", -1)) {
                lineNumber++;
                if (matches.size() > MAX_MATCHES) {
                    return;
                }
                if (pattern.matcher(line).find()) {
                    matches.add(relative + ":" + lineNumber + ":" + shorten(line));
                }
            }
        } catch (IOException | FileToolException e) {
            // One unreadable file does not fail a search over a tree.
        }
    }

    private static boolean isBinary(byte[] head) {
        for (byte value : head) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static String shorten(String line) {
        String trimmed = line.strip();
        return trimmed.length() <= MAX_MATCH_LINE_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_MATCH_LINE_CHARS) + "…";
    }

    // ------------------------------------------------------------------ inputs

    private String read(Path target) throws IOException {
        try (InputStream stream = guard.openForRead(target)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String describe(Path entry) {
        String name = entry.getFileName().toString();
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(
                            entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                return name + "@";
            }
            return attributes.isDirectory() ? name + "/" : name + " (" + attributes.size() + "b)";
        } catch (IOException e) {
            return name + " (unreadable)";
        }
    }

    private static PathMatcher matcher(String pattern) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            throw FileToolException.invalid("'" + pattern + "' is not a usable glob pattern");
        }
    }

    private static Pattern regex(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw FileToolException.invalid(
                    "'" + pattern + "' is not a usable regular expression: " + e.getDescription());
        }
    }

    private static String string(Map<String, Object> args, String key, boolean required) {
        return string(args, key, required, null);
    }

    private static String string(
            Map<String, Object> args, String key, boolean required, String fallback) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            if (required) {
                throw FileToolException.invalid("'" + key + "' is required");
            }
            return fallback;
        }
        return String.valueOf(value);
    }

    private static String content(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw FileToolException.invalid("'" + key + "' is required");
        }
        String text = String.valueOf(value);
        if (text.length() > MAX_WRITE_CHARS) {
            throw FileToolException.invalid(
                    "'"
                            + key
                            + "' is "
                            + text.length()
                            + " characters, over the "
                            + MAX_WRITE_CHARS
                            + " character limit");
        }
        return text;
    }

    private static long number(Map<String, Object> args, String key, long fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw FileToolException.invalid("'" + key + "' expects a number, got '" + value + "'");
        }
    }

    /// Drops the sentinel result collected past the budget.
    private static List<String> trim(List<String> matches) {
        return matches.size() > MAX_MATCHES ? matches.subList(0, MAX_MATCHES) : matches;
    }

    private static String budgetNote() {
        return "more than " + MAX_MATCHES + " matches; narrow the pattern";
    }

    private static String join(List<String> lines, boolean truncated, String reason) {
        if (lines.isEmpty()) {
            return truncated ? "" : "(no results)";
        }
        String body = String.join("\n", lines);
        return truncated ? body + TRUNCATED + reason + "]" : body;
    }

    /// Receives one visited file, answering whether the walk should continue.
    @FunctionalInterface
    private interface Visitor {

        /// @param relative the file's path relative to the confinement root, not null
        /// @param attributes the file's attributes, read without following links, not null
        /// @return true to continue walking, false once a budget is full
        boolean visit(Path relative, BasicFileAttributes attributes);
    }

    /// A wall clock shared by every loop inside one call.
    private record Deadline(long expiresAtNanos) {

        private static Deadline starting() {
            return new Deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALL_TIMEOUT_MS));
        }

        private boolean expired() {
            return System.nanoTime() - expiresAtNanos > 0;
        }
    }

    @Override
    public String toString() {
        return "FileToolProvider[root=" + guard.root() + "]";
    }
}
