package io.hensu.cli.sandbox;

import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.HermeticEnvironment;
import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.tool.ToolCallStatus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/// The single engine that runs every command, for workflows and for agents alike.
///
/// Its job is to make the guarantees of the execution security model true at the
/// moment a process starts: arguments are bound as whole argv elements so no
/// shell parses agent data, the environment is rebuilt from nothing so host
/// credentials cannot ride along, containment is applied or the call is refused,
/// and a wall clock bounds the entire process tree.
///
/// ### The one exception, and why it is safe to have
/// A `rung: true` entry hands the agent's own text to `/bin/sh`. That is a
/// smaller concession than it reads, because many ordinary grants already execute
/// *content* rather than a fixed program: an interpreter runs a script, a build
/// tool runs a build file, a migration runner runs migrations. Wherever the agent
/// can write that content, the catalog has already granted arbitrary code
/// execution inside the sandbox – it arrives as data rather than as a command
/// line, which changes how it looks and not what it can do. Refusing a rung on
/// such a path buys almost nothing in containment while taxing every honest step.
/// What the catalog governs there is which grants an entry point carries, and a
/// rung is pinned by {@link CommandDefinition} to the closed policy: no network,
/// no cache mounts, writes confined to the working directory and the call's
/// private home. Both shell command lines are built in {@link #bind}, and only
/// there, so the claim survives as a property of the code's shape.
///
/// ### Two phases on purpose
/// {@link #prepare} decides everything and launches nothing; {@link #execute}
/// runs exactly what was decided. An approval gate can therefore show a reviewer
/// the real argv and the real policy, not a rendering of a template, and then run
/// the same object it showed. {@link #run} is the convenience for callers with no
/// gate.
///
/// ### The command gets a private home
/// A sandbox binds system paths read-only and the working directory, so `$HOME`
/// would otherwise point at a directory that does not exist inside the sandbox –
/// and every build tool wants a home to keep state in. Each call therefore gets a
/// fresh writable home that is deleted afterwards. Calls are hermetic by default:
/// state can be written and none of it survives into the next call. `cache:`
/// entries are the operator's opt-out, because a permanently cold cache would
/// force every build to re-download the world over the network it was told not to
/// have.
///
/// ### What it deliberately does not do
/// It cannot cancel a call already in flight: the engine runs no watchdog around
/// a tool invocation, so the timeout here is the only bound that exists. It also
/// makes no judgement about whether an in-scope write is wise – that is what the
/// approval gate is for.
///
/// @implNote **Immutable after construction. Thread-safe.** Parallel workflow
/// branches may run commands concurrently; each call gets its own scratch
/// directory and its own process, so isolation comes from the kernel rather than
/// from locking.
/// @see SandboxLauncher for the containment backends
/// @see CommandResult for the outcome vocabulary
public final class CommandRunner {

    /// System property an operator sets to run commands without containment.
    ///
    /// Setting it is a visible, logged decision, never a default and never a
    /// silent fallback when a backend is missing.
    public static final String ALLOW_UNSANDBOXED_PROPERTY = "toolexec.allowUnsandboxed";

    private static final Logger logger = Logger.getLogger(CommandRunner.class.getName());
    private static final ExecutorService PROCESS_IO_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;
    private static final String TRUNCATION_NOTE =
            "\n[output truncated at " + MAX_OUTPUT_CHARS + " characters]";
    private static final long DRAIN_GRACE_MS = 5_000L;

    private final SandboxLauncher launcher;
    private final Map<String, String> hostEnvironment;
    private final boolean allowUnsandboxed;

    /// Creates a runner over an explicit backend and host environment.
    ///
    /// @param launcher the containment backend, not null
    /// @param hostEnvironment the environment to draw
    ///     {@link HermeticEnvironment#PASSTHROUGH} from – `System.getenv()` in
    ///     production, a fixture in tests, not null
    /// @param allowUnsandboxed whether to run commands when no backend works
    public CommandRunner(
            SandboxLauncher launcher,
            Map<String, String> hostEnvironment,
            boolean allowUnsandboxed) {
        this.launcher = launcher;
        this.hostEnvironment = Map.copyOf(hostEnvironment);
        this.allowUnsandboxed = allowUnsandboxed;
    }

    /// Creates the runner a CLI process uses, over the platform backend and the
    /// real environment.
    ///
    /// @return the runner, never null
    public static CommandRunner forHost() {
        boolean allowUnsandboxed = Boolean.getBoolean(ALLOW_UNSANDBOXED_PROPERTY);
        if (allowUnsandboxed) {
            logger.warning(
                    ALLOW_UNSANDBOXED_PROPERTY
                            + " is set: commands will run without OS containment.");
        }
        return new CommandRunner(SandboxLauncher.forCurrentOs(), System.getenv(), allowUnsandboxed);
    }

    /// Prepares and runs a command in one step.
    ///
    /// @param command the compiled catalog entry, not null
    /// @param params values for the command's declared parameters, not null
    /// @param workingDir the directory to run in, not null
    /// @return the outcome, never null
    public CommandResult run(
            CommandDefinition command, Map<String, Object> params, Path workingDir) {
        PreparedCommand prepared;
        try {
            prepared = prepare(command, params, workingDir);
        } catch (CommandPrepareException e) {
            return e.result();
        }
        return execute(prepared);
    }

    /// Decides everything about a command without launching anything.
    ///
    /// ### Contracts
    /// - **Postcondition**: on success, every `write:` subtree, every `cache:`
    ///   entry and the private home exist on disk, and the returned argv is final
    /// - **Postcondition**: on failure, nothing was spawned and the scratch
    ///   directory has been removed
    ///
    /// @apiNote **Side effects**: creates the call's scratch directory, its
    /// private home, any missing `write:` directory inside the working directory,
    /// and any missing `cache:` directory.
    ///
    /// @param command the compiled catalog entry, not null
    /// @param params values for the command's declared parameters, not null –
    ///     keys the command does not declare are ignored
    /// @param workingDir the directory to run in, not null
    /// @return the prepared command, never null
    /// @throws CommandPrepareException if arguments fail their schema or no
    ///     sandbox backend can contain the call
    public PreparedCommand prepare(
            CommandDefinition command, Map<String, Object> params, Path workingDir) {
        Path workingDirectory = workingDir.toAbsolutePath().normalize();
        List<String> argv = bind(command, params);
        Map<String, String> parameterVariables =
                command.shellMode() ? parameterVariables(command, params) : Map.of();

        Path callDirectory = createCallDirectory();
        try {
            Path privateHome = Files.createDirectory(callDirectory.resolve("home"));
            SandboxPolicy policy = command.sandbox();
            createWritePaths(policy, workingDirectory);
            createCachePaths(policy);

            String sandboxState;
            List<String> finalArgv;
            if (launcher.isAvailable()) {
                sandboxState = launcher.backendName();
                finalArgv = launcher.wrap(argv, policy, workingDirectory, privateHome);
            } else if (allowUnsandboxed) {
                logger.warning(
                        "Running '"
                                + argv.getFirst()
                                + "' without containment because "
                                + ALLOW_UNSANDBOXED_PROPERTY
                                + " is set ("
                                + launcher.unavailabilityReason()
                                + ")");
                sandboxState = PreparedCommand.UNSANDBOXED_OVERRIDE;
                finalArgv = argv;
            } else {
                throw new CommandPrepareException(
                        CommandResult.sandboxUnavailable(launcher.unavailabilityReason()));
            }

            return new PreparedCommand(
                    finalArgv,
                    environment(command, policy, privateHome, parameterVariables),
                    workingDirectory,
                    command.timeoutMs(),
                    policy,
                    sandboxState,
                    callDirectory);
        } catch (IOException e) {
            deleteRecursively(callDirectory);
            throw new CommandPrepareException(
                    CommandResult.sandboxUnavailable(
                            "could not prepare the sandbox filesystem: " + e.getMessage()),
                    e);
        } catch (RuntimeException e) {
            deleteRecursively(callDirectory);
            throw e;
        }
    }

    /// Returns a runner that will launch without containment when no backend works.
    ///
    /// The only caller is the approval path: a reviewer who was shown an uncontained
    /// invocation and approved it has made the decision this flag represents, for that one
    /// call. Everything else keeps the refusing runner, so an absent sandbox can never
    /// become a silent uncontained launch.
    ///
    /// @return a runner sharing this one's backend and environment, allowing the override
    /// @see io.hensu.core.tool.UncontainedOnApproval for the capability this serves
    public CommandRunner allowingUnsandboxed() {
        return allowUnsandboxed ? this : new CommandRunner(launcher, hostEnvironment, true);
    }

    /// Releases the scratch filesystem a {@link #prepare} created, without running anything.
    ///
    /// {@link #execute} does this itself. A caller that prepared a command only to describe
    /// it — the approval preview — has to say so, or every previewed call leaves a private
    /// home behind in the temporary directory.
    ///
    /// @param prepared the invocation to discard, not null
    /// @apiNote **Side effects**: deletes the prepared call's directory tree. The
    ///     {@link PreparedCommand} must not be executed afterwards.
    public void discard(PreparedCommand prepared) {
        deleteRecursively(prepared.callDirectory());
    }

    /// Runs a prepared command and returns what it did.
    ///
    /// ### Contracts
    /// - **Postcondition**: no process from this call survives the return – the
    ///   PID namespace handles it on Linux, an explicit descendant sweep on macOS
    /// - **Postcondition**: the call's scratch directory, including its private
    ///   home, has been deleted
    ///
    /// @param prepared the command decided by {@link #prepare}, not null
    /// @return the outcome, never null
    public CommandResult execute(PreparedCommand prepared) {
        logger.info(
                "Executing ["
                        + prepared.sandboxState()
                        + "] "
                        + prepared.argv()
                        + " in "
                        + prepared.workingDir());
        try {
            return launch(prepared);
        } finally {
            deleteRecursively(prepared.callDirectory());
        }
    }

    private CommandResult launch(PreparedCommand prepared) {
        ProcessBuilder builder = new ProcessBuilder(prepared.argv());
        builder.directory(prepared.workingDir().toFile());
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(prepared.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // The backend probed clean, so a launch failure is the supervisor refusing.
            return PreparedCommand.UNSANDBOXED_OVERRIDE.equals(prepared.sandboxState())
                    ? new CommandResult(
                            ToolCallStatus.FAILURE,
                            CommandResult.NO_PROCESS,
                            "",
                            "could not start the command: " + e.getMessage())
                    : CommandResult.sandboxRefused(
                            "the sandbox refused this invocation: " + e.getMessage());
        }

        OutputBuffer output = new OutputBuffer();
        var drain = PROCESS_IO_EXECUTOR.submit(() -> drain(process, output));
        try {
            if (!process.waitFor(prepared.timeoutMs(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                drain.cancel(true);
                return CommandResult.timeout(prepared.timeoutMs(), output.snapshot());
            }
            drain.get(DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
            int exitCode = process.exitValue();
            return exitCode == 0
                    ? CommandResult.success(output.snapshot())
                    : CommandResult.failure(exitCode, output.snapshot());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            drain.cancel(true);
            return CommandResult.timeout(prepared.timeoutMs(), output.snapshot());
        } catch (Exception e) {
            killTree(process);
            drain.cancel(true);
            return new CommandResult(
                    ToolCallStatus.FAILURE,
                    CommandResult.NO_PROCESS,
                    output.snapshot(),
                    "command execution failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- binding

    /// Substitutes declared parameters into the argv template as whole tokens.
    ///
    /// A value never becomes syntax: a scalar occupies exactly the element its
    /// placeholder sat in, and a list contributes one element per item. There is
    /// no word splitting to defeat and no metacharacter to escape, because
    /// nothing downstream re-parses the result.
    ///
    /// The rung is the deliberate exception and stays inside this method for the
    /// same reason the shell form does: one place in the codebase builds a shell
    /// command line, so the claim can be checked by reading the sources rather
    /// than by trusting every call site.
    private List<String> bind(CommandDefinition command, Map<String, Object> params) {
        validate(command, params);
        if (command.rung()) {
            return List.of(
                    "/bin/sh", "-c", String.valueOf(params.get(CommandDefinition.RUNG_PARAM)));
        }
        if (command.shellMode()) {
            return List.of("/bin/sh", "-c", command.shellCommand());
        }
        List<String> argv = new ArrayList<>();
        for (String element : command.execTemplate()) {
            Matcher matcher = CommandDefinition.PLACEHOLDER.matcher(element);
            if (!matcher.find()) {
                argv.add(element);
                continue;
            }
            ParamSpec spec = command.toolSpec().param(matcher.group(1)).orElseThrow();
            Object value = params.get(spec.name());
            if (value == null) {
                continue;
            }
            if (spec.isList()) {
                ((List<?>) value).forEach(item -> argv.add(String.valueOf(item)));
            } else {
                argv.add(
                        element.substring(0, matcher.start())
                                + value
                                + element.substring(matcher.end()));
            }
        }
        return argv;
    }

    private void validate(CommandDefinition command, Map<String, Object> params) {
        for (ParamSpec spec : command.params()) {
            Optional<String> failure = spec.validate(params.get(spec.name()));
            if (failure.isPresent()) {
                throw new CommandPrepareException(CommandResult.validationFailure(failure.get()));
            }
        }
    }

    /// Maps declared parameters onto their reserved environment variables.
    ///
    /// This is the only channel a shell-mode command receives parameters through:
    /// the shell text itself is fixed and human-authored, and a value read with
    /// `getenv` is never re-parsed as syntax.
    private static Map<String, String> parameterVariables(
            CommandDefinition command, Map<String, Object> params) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (ParamSpec spec : command.params()) {
            Object value = params.get(spec.name());
            if (value != null) {
                variables.put(spec.environmentVariable(), String.valueOf(value));
            }
        }
        return variables;
    }

    // ------------------------------------------------------------- environment

    /// Builds the child's complete environment from nothing.
    ///
    /// Starting from an empty map rather than the host's is what makes the
    /// guarantee absolute: a credential that was never copied cannot leak, and an
    /// inherited `HENSU_PARAM_*` variable cannot impersonate a declared parameter.
    private Map<String, String> environment(
            CommandDefinition command,
            SandboxPolicy policy,
            Path privateHome,
            Map<String, String> parameterVariables) {
        Map<String, String> environment = HermeticEnvironment.base(hostEnvironment);
        environment.put("HOME", privateHome.toString());
        environment.put("XDG_CACHE_HOME", privateHome.resolve(".cache").toString());
        environment.put("XDG_CONFIG_HOME", privateHome.resolve(".config").toString());
        environment.put("XDG_DATA_HOME", privateHome.resolve(".local/share").toString());
        environment.put("GRADLE_USER_HOME", privateHome.resolve(".gradle").toString());
        environment.put("npm_config_cache", privateHome.resolve(".npm").toString());

        // A mounted cache replaces the private home for the tool that owns it.
        // The match is on the directory's own name, so a mount is never widened
        // beyond what the operator wrote down.
        for (String cachePath : policy.cachePaths()) {
            Path cache = Path.of(cachePath).toAbsolutePath().normalize();
            switch (cache.getFileName().toString()) {
                case ".gradle" -> environment.put("GRADLE_USER_HOME", cache.toString());
                case ".npm" -> environment.put("npm_config_cache", cache.toString());
                case ".cache" -> environment.put("XDG_CACHE_HOME", cache.toString());
                default -> {
                    // Bound and visible at its own path; the command points at it
                    // itself through env: if it needs a variable.
                }
            }
        }

        environment.putAll(command.environment());
        environment.putAll(parameterVariables);
        return environment;
    }

    // ------------------------------------------------------------- filesystem

    private static Path createCallDirectory() {
        try {
            return Files.createTempDirectory("hensu-tool-");
        } catch (IOException e) {
            throw new CommandPrepareException(
                    CommandResult.sandboxUnavailable(
                            "could not create a scratch directory: " + e.getMessage()),
                    e);
        }
    }

    /// Creates any missing `write:` directory, re-checking that it is still inside
    /// the working directory after symlinks have been resolved.
    private static void createWritePaths(SandboxPolicy policy, Path workingDir) throws IOException {
        for (String writePath : policy.writePaths()) {
            Path target = workingDir.resolve(writePath).toAbsolutePath().normalize();
            Files.createDirectories(target);
            Path real = target.toRealPath();
            if (!real.startsWith(workingDir.toRealPath())) {
                throw new CommandPrepareException(
                        CommandResult.validationFailure(
                                "write path '"
                                        + writePath
                                        + "' resolves to "
                                        + real
                                        + ", outside the working directory"));
            }
        }
    }

    /// Creates any missing `cache:` directory, because bubblewrap aborts on a
    /// bind source that does not exist.
    private static void createCachePaths(SandboxPolicy policy) throws IOException {
        for (String cachePath : policy.cachePaths()) {
            Files.createDirectories(Path.of(cachePath));
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(CommandRunner::deleteQuietly);
        } catch (IOException e) {
            logger.warning(
                    "Could not remove the scratch directory " + root + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warning("Could not remove " + path + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ output

    private static Void drain(Process process, OutputBuffer output) {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(buffer, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    /// The merged stdout and stderr of one command, capped and readable at any moment.
    ///
    /// The drain thread appends while the calling thread reads: a timeout or a
    /// failed launch must still report whatever the command managed to say, so
    /// {@link #snapshot()} is deliberately callable before the drain has finished.
    /// The buffer owns the lock both sides take rather than leaving callers to
    /// agree on one.
    private static final class OutputBuffer {

        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        /// Appends the first `length` characters of `buffer`, or the truncation
        /// note once the cap is reached.
        private synchronized void append(char[] buffer, int length) {
            if (text.length() >= MAX_OUTPUT_CHARS) {
                if (!truncated) {
                    truncated = true;
                    text.append(TRUNCATION_NOTE);
                }
                return;
            }
            text.append(buffer, 0, length);
        }

        /// Returns what has been captured so far.
        ///
        /// @return the trimmed output, never null
        private synchronized String snapshot() {
            return text.toString().trim();
        }
    }

    // ----------------------------------------------------------------- lifetime

    /// Kills the process and everything it spawned.
    ///
    /// On Linux the PID namespace has already done this when the supervisor dies;
    /// the descendant sweep is the macOS path, where no such namespace exists.
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /// Returns the platform backend this runner contains commands with.
    ///
    /// @return the backend, never null
    public SandboxLauncher launcher() {
        return launcher;
    }
}
