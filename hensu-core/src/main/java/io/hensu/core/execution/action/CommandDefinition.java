package io.hensu.core.execution.action;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// One entry of the command catalog, compiled and checked when the catalog loads.
///
/// A definition is in exactly one of three forms, and the difference is where
/// agent data is allowed to go:
///
/// - **argv form** ({@link #execTemplate()}): a list of tokens where each element
///   is either a literal or carries one `{param}` placeholder. Placeholders are
///   substituted as whole elements and handed to `execve`, so a shell never sees
///   them and metacharacters in an argument are inert bytes.
/// - **shell form** ({@link #shellCommand()}): fixed, human-authored text run
///   through `/bin/sh`. The text may not contain placeholders at all; parameters
///   reach it only as {@link ParamSpec#ENV_PREFIX} environment variables, which
///   no shell re-parses.
/// - **rung form** ({@link #rung()}): the agent writes the command line itself.
///   A catalog enumerates the operations a deployment knows it wants; it cannot
///   enumerate the tail of a general capability – the one-off check, the ad-hoc
///   probe, the step that matters once – and an unattended run has no operator to
///   add an entry mid-run. It is reachable only where an operator declared
///   `rung: true` on an entry.
///
/// The first two forms let no agent contribute text that is parsed as syntax –
/// that property is what makes escaping unnecessary rather than merely careful.
/// The rung gives that property up deliberately and buys nothing back with
/// escaping, which could not be made sound anyway; what contains it is the
/// sandbox, and its policy is therefore not negotiable. A rung is pinned to
/// {@link SandboxPolicy#restrictive()} and to an empty environment here, so a
/// widened policy cannot reach a rung even from code that bypassed the catalog.
///
/// ### Contracts
/// - **Precondition**: exactly one of `execTemplate`, `shellCommand` and `rung` is set
/// - **Precondition**: a rung carries a {@link ToolSpec}, no environment, and
///   {@link SandboxPolicy#restrictive()}
/// - **Postcondition**: in argv form, element zero is an absolute path resolved
///   when the catalog loaded, so a later `PATH` change cannot swap the binary
/// - **Invariant**: `sandbox` is never null; a command that declares nothing gets
///   {@link SandboxPolicy#restrictive()}
///
/// @param execTemplate the argv template with element zero already resolved to an
///     absolute path, null in the other forms
/// @param shellCommand the fixed shell text, null in the other forms
/// @param timeoutMs wall clock bounding the whole process tree, positive
/// @param environment extra environment variables for the child, not null (may be empty)
/// @param toolSpec the agent-visible schema, null when the command is not exposed
/// @param sandbox the containment scope, not null
/// @param unattended whether an unattended run may invoke this without approval
/// @param approvalRequired whether an attended run must route this through review
/// @param rung whether the agent supplies the command line, under the fixed policy
/// @implNote **Immutable after construction.** Safe to share across Virtual Threads.
/// @see CommandRegistry for the catalog that compiles these
/// @see SandboxPolicy for the containment half of the contract
public record CommandDefinition(
        List<String> execTemplate,
        String shellCommand,
        long timeoutMs,
        Map<String, String> environment,
        ToolSpec toolSpec,
        SandboxPolicy sandbox,
        boolean unattended,
        boolean approvalRequired,
        boolean rung) {

    /// Wall clock applied to a command that declares no `timeout:`.
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /// Matches one `{param}` reference inside an argv template element.
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /// The single parameter a rung takes, carrying the agent's command line.
    ///
    /// A rung declares no `params:` of its own: its schema is fixed so that the
    /// one free-text parameter in the whole catalog is the one the sandbox policy
    /// is pinned around, rather than something an entry can grow by accident.
    public static final String RUNG_PARAM = "command";

    /// Longest command line a rung accepts.
    ///
    /// A bound exists so a runaway model cannot hand `/bin/sh` a megabyte of
    /// generated text; it is generous enough for any command line worth writing
    /// by hand.
    public static final int RUNG_MAX_LENGTH = 4096;

    /// Compact constructor with validation and defensive copies.
    ///
    /// @throws IllegalArgumentException if the forms do not sum to exactly one,
    ///     if the timeout is not positive, or if a rung carries a widened policy,
    ///     an environment, or no tool schema
    public CommandDefinition {
        boolean hasExec = execTemplate != null && !execTemplate.isEmpty();
        boolean hasShell = shellCommand != null && !shellCommand.isBlank();
        int forms = (hasExec ? 1 : 0) + (hasShell ? 1 : 0) + (rung ? 1 : 0);
        if (forms != 1) {
            throw new IllegalArgumentException(
                    "a command must declare exactly one of exec:, shell: + command:, and"
                            + " rung: true");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeoutMs);
        }
        if (rung) {
            if (toolSpec == null) {
                throw new IllegalArgumentException("a rung must carry a tool schema");
            }
            if (sandbox != null && !SandboxPolicy.restrictive().equals(sandbox)) {
                throw new IllegalArgumentException(
                        "a rung runs under the closed policy and cannot be widened, got "
                                + sandbox);
            }
            if (environment != null && !environment.isEmpty()) {
                throw new IllegalArgumentException("a rung receives no extra environment");
            }
        }
        execTemplate = hasExec ? List.copyOf(execTemplate) : null;
        shellCommand = hasShell ? shellCommand : null;
        environment = environment != null ? Map.copyOf(environment) : Map.of();
        sandbox = sandbox != null ? sandbox : SandboxPolicy.restrictive();
    }

    /// Creates a non-rung definition, which is every form the catalog had before
    /// the rung existed.
    ///
    /// @param execTemplate the argv template, null in shell form
    /// @param shellCommand the fixed shell text, null in argv form
    /// @param timeoutMs wall clock bounding the whole process tree, positive
    /// @param environment extra environment variables for the child, not null
    /// @param toolSpec the agent-visible schema, null when not exposed
    /// @param sandbox the containment scope, not null
    /// @param unattended whether an unattended run may invoke this
    /// @param approvalRequired whether an attended run must route this through review
    public CommandDefinition(
            List<String> execTemplate,
            String shellCommand,
            long timeoutMs,
            Map<String, String> environment,
            ToolSpec toolSpec,
            SandboxPolicy sandbox,
            boolean unattended,
            boolean approvalRequired) {
        this(
                execTemplate,
                shellCommand,
                timeoutMs,
                environment,
                toolSpec,
                sandbox,
                unattended,
                approvalRequired,
                false);
    }

    /// Builds the fixed schema every rung is exposed to agents through.
    ///
    /// @param description the sentence the model reads, not null
    /// @return the schema carrying only {@link #RUNG_PARAM}, never null
    public static ToolSpec rungToolSpec(String description) {
        return new ToolSpec(
                description,
                List.of(
                        new ParamSpec(
                                RUNG_PARAM,
                                "string",
                                true,
                                null,
                                List.of(),
                                RUNG_MAX_LENGTH,
                                false)));
    }

    /// Creates a rung with catalog defaults.
    ///
    /// @param toolDescription the sentence the model reads, not null
    /// @param timeoutMs wall clock bounding the whole process tree, positive
    /// @return the definition, never null
    public static CommandDefinition rung(String toolDescription, long timeoutMs) {
        return new CommandDefinition(
                null,
                null,
                timeoutMs,
                Map.of(),
                rungToolSpec(toolDescription),
                SandboxPolicy.restrictive(),
                true,
                false,
                true);
    }

    /// Creates an argv-form command with catalog defaults and no agent exposure.
    ///
    /// @param execTemplate the argv template, not null or empty
    /// @return the definition, never null
    public static CommandDefinition exec(List<String> execTemplate) {
        return new CommandDefinition(
                execTemplate,
                null,
                DEFAULT_TIMEOUT_MS,
                Map.of(),
                null,
                SandboxPolicy.restrictive(),
                false,
                false);
    }

    /// Creates a shell-form command with catalog defaults and no agent exposure.
    ///
    /// @param shellCommand the fixed shell text, not null or blank
    /// @return the definition, never null
    public static CommandDefinition shell(String shellCommand) {
        return new CommandDefinition(
                null,
                shellCommand,
                DEFAULT_TIMEOUT_MS,
                Map.of(),
                null,
                SandboxPolicy.restrictive(),
                false,
                false);
    }

    /// Returns whether this command is the fixed, human-authored shell-text form.
    ///
    /// This is narrower than "runs through `/bin/sh`": a rung does too, but its
    /// text comes from the agent and it receives no
    /// {@link ParamSpec#ENV_PREFIX} variables, so the two forms are not
    /// interchangeable at the point where parameters are bound.
    ///
    /// @return true in shell form, false in argv and rung form
    public boolean shellMode() {
        return shellCommand != null;
    }

    /// Returns whether agents may see and call this command.
    ///
    /// @return true when the catalog declared a `tool:` block
    public boolean agentVisible() {
        return toolSpec != null;
    }

    /// Returns the declared parameters, or an empty list when the command is not exposed.
    ///
    /// @return the parameter schemas, never null (may be empty)
    public List<ParamSpec> params() {
        return toolSpec != null ? toolSpec.params() : List.of();
    }
}
