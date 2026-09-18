package io.hensu.cli.tool;

import io.hensu.cli.sandbox.CommandPrepareException;
import io.hensu.cli.sandbox.CommandResult;
import io.hensu.cli.sandbox.CommandRunner;
import io.hensu.cli.sandbox.PreparedCommand;
import io.hensu.core.execution.action.CommandDefinition;
import io.hensu.core.execution.action.ParamSpec;
import io.hensu.core.execution.action.SandboxPolicy;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolDefinition.ParameterDef;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.ToolProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Publishes the commands a deployment declared as agent-callable tools.
///
/// The catalog is the allowlist, and `tool:` is the opt-in: an entry without one
/// stays usable from a workflow's `execute(...)` and invisible to every agent.
/// Nothing here decides *whether* a call may proceed – that is the approval
/// decorator's job – so this class maps in one direction only, from a declared
/// entry to a tool definition and from a {@link CommandResult} back to a
/// {@link ToolCallResult}.
///
/// That mapping is deliberately field for field. {@link CommandResult} already
/// carries a {@link ToolCallStatus} and an exit code, so there is no translation
/// table to keep in sync and the audit record inherits both from the process
/// that produced them.
///
/// ### Contracts
/// - **Precondition**: the catalog has been pointed at a working directory
/// - **Postcondition**: only entries carrying a `tool:` block are ever published
/// - **Invariant**: a parameter declared `secret` is published as sensitive, so
///   the loop redacts its value before any sink sees it
///
/// @implNote **Immutable after construction.** Holds the shared catalog and one
/// runner; both are safe for concurrent use, so parallel branches may call
/// tools at the same time.
/// @see CommandCatalog for the shared catalog
/// @see CommandRunner for the two-phase execution this delegates to
@Singleton
public class CommandToolProvider implements ToolProvider, PreviewCapable {

    private final CommandCatalog catalog;
    private final CommandRunner runner;

    /// Creates a provider over the shared catalog, running commands on the host.
    ///
    /// @param catalog the shared command catalog, not null
    @Inject
    public CommandToolProvider(CommandCatalog catalog) {
        this(catalog, CommandRunner.forHost());
    }

    /// Creates a provider over an explicit runner.
    ///
    /// @param catalog the shared command catalog, not null
    /// @param runner the execution engine to delegate to, not null
    /// @apiNote Test seam, so provider behaviour can be exercised without
    ///     launching processes.
    public CommandToolProvider(CommandCatalog catalog, CommandRunner runner) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    /// Returns the declared commands that opted into agent visibility.
    ///
    /// @return one definition per entry carrying a `tool:` block, never null
    @Override
    public List<ToolDefinition> tools() {
        List<ToolDefinition> published = new ArrayList<>();
        catalog.registry()
                .agentVisibleCommands()
                .forEach((id, definition) -> published.add(describe(id, definition)));
        return List.copyOf(published);
    }

    /// Returns whether the catalog publishes this name to agents.
    ///
    /// @param toolName the tool identifier to check, not null
    /// @return true if a command of that id declares a `tool:` block
    @Override
    public boolean provides(String toolName) {
        CommandDefinition definition = catalog.registry().getCommand(toolName);
        return definition != null && definition.agentVisible();
    }

    /// Runs a declared command and reports its outcome unchanged.
    ///
    /// @param toolName the command id the agent chose, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @param context the workflow state context, not null and unused – a
    ///     command binds only its declared parameters, never ambient state
    /// @return the command's result, never null
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        CommandDefinition definition = visible(toolName);
        if (definition == null) {
            return ToolCallResult.of(
                    toolName,
                    ToolCallStatus.UNKNOWN_TOOL,
                    null,
                    "'" + toolName + "' is not a command this deployment offers to agents",
                    null);
        }
        CommandResult result =
                runner.run(
                        definition,
                        arguments != null ? arguments : Map.of(),
                        catalog.workingDirectory());
        return toToolResult(toolName, result);
    }

    /// Resolves what a command call would run, without running it.
    ///
    /// The preview goes through {@link CommandRunner#prepare} and the approval
    /// decorator executes the very same {@link PreparedCommand}, so a reviewer
    /// reads the argv that will run rather than a re-rendering of it.
    ///
    /// @param toolName the command id to describe, not null
    /// @param arguments the agent's arguments, not null (may be empty)
    /// @return the resolved argv, containment summary and unattended flag, never null
    /// @throws IllegalArgumentException if the catalog does not publish this name
    @Override
    public ToolPreview preview(String toolName, Map<String, Object> arguments) {
        CommandDefinition definition = visible(toolName);
        if (definition == null) {
            throw new IllegalArgumentException("Not an agent-visible command: " + toolName);
        }
        try {
            PreparedCommand prepared =
                    runner.prepare(
                            definition,
                            arguments != null ? arguments : Map.of(),
                            catalog.workingDirectory());
            return new ToolPreview(
                    toolName + ": " + String.join(" ", prepared.argv()),
                    prepared.argv(),
                    summarize(prepared.policy()),
                    definition.unattended());
        } catch (CommandPrepareException e) {
            // Preparation failing is itself worth showing: "this would not have
            // run, and here is why" is a better review than a blank argv.
            return new ToolPreview(
                    toolName + ": cannot be prepared – " + e.result().message(),
                    List.of(),
                    summarize(definition.sandbox()),
                    definition.unattended());
        }
    }

    // ------------------------------------------------------------------ mapping

    private CommandDefinition visible(String toolName) {
        CommandDefinition definition = catalog.registry().getCommand(toolName);
        return definition != null && definition.agentVisible() ? definition : null;
    }

    /// Maps a declared entry onto the descriptor an agent chooses from.
    private static ToolDefinition describe(String id, CommandDefinition definition) {
        List<ParameterDef> parameters = new ArrayList<>();
        for (ParamSpec spec : definition.params()) {
            parameters.add(
                    new ParameterDef(
                            spec.name(),
                            spec.type(),
                            describe(spec),
                            spec.required(),
                            null,
                            spec.secret()));
        }
        return ToolDefinition.of(id, definition.toolSpec().description(), parameters);
    }

    /// Turns a parameter's constraints into the sentence a model reads.
    ///
    /// The model cannot see the schema the loader enforces, so a constraint that
    /// is not described is a validation failure waiting to happen.
    private static String describe(ParamSpec spec) {
        StringBuilder text = new StringBuilder(spec.type());
        if (!spec.enumValues().isEmpty()) {
            text.append(", one of ").append(spec.enumValues());
        }
        if (spec.pattern() != null) {
            text.append(", matching ").append(spec.pattern());
        }
        if (spec.maxLength() != null) {
            text.append(", at most ").append(spec.maxLength()).append(" characters");
        }
        if (spec.secret()) {
            text.append("; the value is redacted from logs and the audit trail");
        }
        return text.toString();
    }

    /// Copies an execution outcome across without reinterpreting it.
    private static ToolCallResult toToolResult(String toolName, CommandResult result) {
        Integer exitCode = result.exitCode() == CommandResult.NO_PROCESS ? null : result.exitCode();
        return result.success()
                ? ToolCallResult.success(toolName, result.output(), exitCode)
                : ToolCallResult.of(
                        toolName,
                        result.status(),
                        result.output().isEmpty() ? null : result.output(),
                        result.message(),
                        exitCode);
    }

    /// Renders a containment policy as the one line a reviewer reads.
    static String summarize(SandboxPolicy policy) {
        String writes =
                policy.writePaths().isEmpty() ? "nothing" : String.join(", ", policy.writePaths());
        String caches =
                policy.cachePaths().isEmpty()
                        ? ""
                        : ", caches: " + String.join(", ", policy.cachePaths());
        return "network: " + (policy.network() ? "on" : "off") + ", writes: " + writes + caches;
    }
}
