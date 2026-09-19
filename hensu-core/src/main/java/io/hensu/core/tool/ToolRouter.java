package io.hensu.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Composes several {@link ToolProvider} instances into one tool surface.
///
/// The router is the single object the engine wires into an execution context:
/// it answers discovery questions as a {@link ToolRegistry} and performs
/// invocation as a {@link ToolInvoker}, routing each call to the provider that
/// owns the requested name. Runtimes differ only in which providers they
/// contribute – the engine never learns whether a tool came from an MCP server
/// or a local command.
///
/// ### Failing providers are absent, not fatal
/// A provider that throws while reporting its catalog – an MCP server that is
/// down, a config file that has become unreadable – is logged and skipped for
/// that read. Its tools are simply missing, so an agent declaring one fails its
/// own node with a declared-versus-available diff that transitions can route
/// around, instead of aborting the whole execution.
///
/// ### Duplicate names
/// Two configured providers exposing the same tool name is a configuration
/// error, because the router cannot decide which one the agent meant. The
/// constructor checks for collisions, but that check is only best-effort: a
/// provider whose catalog is dynamic (tenant-scoped on the server, lazily
/// started on the CLI) reports nothing yet at construction time. The
/// authoritative check therefore runs again on every catalog materialization –
/// {@link #all()} and {@link #get} reject collisions, and {@link #call} reports
/// {@link ToolCallStatus#CATALOG_ERROR} – so a duplicate surfaces before any
/// tool executes and never reaches the model as an ordinary tool failure.
///
/// ### Built-ins yield
/// A {@link BuiltInToolProvider} is exempt from that rule in one direction. Its
/// tools ship with the engine rather than being wired by anyone, so a collision
/// with a configured source is resolved in the configured source's favour: the
/// built-in name is dropped from the catalog and a warning names the winner.
/// Collisions between configured providers, and between two built-ins, still
/// abort.
///
/// ### Thread Safety
/// @implNote **Immutable after construction.** Safe for concurrent use. Thread
/// safety of the catalog itself is each provider's responsibility.
///
/// @see ToolProvider for contributing a tool source
/// @see BuiltInToolProvider for the precedence carve-out
public final class ToolRouter implements ToolRegistry, ToolInvoker {

    private static final Logger logger = Logger.getLogger(ToolRouter.class.getName());

    /// Upper bound on how many decorators may sit in front of one provider.
    private static final int MAX_DECORATOR_DEPTH = 16;

    private final List<ToolProvider> configured;
    private final List<ToolProvider> builtIns;

    /// Creates a router over the given providers.
    ///
    /// @param providers tool sources to compose, not null (may be empty)
    /// @throws NullPointerException if providers is null or contains null
    /// @throws IllegalStateException if two providers of the same precedence
    ///     already expose the same tool name
    public ToolRouter(List<ToolProvider> providers) {
        List<ToolProvider> all = List.copyOf(providers);
        this.configured =
                all.stream().filter(p -> !(unwrap(p) instanceof BuiltInToolProvider)).toList();
        this.builtIns = all.stream().filter(p -> unwrap(p) instanceof BuiltInToolProvider).toList();
        collect(ToolProvider::settledTools);
    }

    /// Returns a router with no providers, exposing no tools.
    ///
    /// @return empty router, never null
    public static ToolRouter empty() {
        return new ToolRouter(List.of());
    }

    /// Looks up a tool across the live catalogs of all providers.
    ///
    /// @param name the tool identifier to look up, not null
    /// @return the tool definition if the catalog exposes it, empty otherwise
    /// @throws NullPointerException if name is null
    /// @throws IllegalStateException if two providers of the same precedence expose this name
    @Override
    public Optional<ToolDefinition> get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return collectValidated().stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /// Returns the union of every provider's live catalog.
    ///
    /// This is the authoritative duplicate check: the tool loop resolves an
    /// agent's declared tools through this method, so a colliding catalog fails
    /// the node instead of silently routing to an arbitrary provider.
    ///
    /// @return unmodifiable union of all provider catalogs, never null (may be empty)
    /// @throws IllegalStateException if two providers of the same precedence
    ///     expose the same tool name
    @Override
    public List<ToolDefinition> all() {
        return collectValidated();
    }

    /// Routes an invocation to the provider owning the tool.
    ///
    /// Never throws for a routing problem: an unroutable name yields
    /// {@link ToolCallStatus#UNKNOWN_TOOL} and a colliding name yields
    /// {@link ToolCallStatus#CATALOG_ERROR}, so the caller decides whether the
    /// model sees the outcome or the node simply fails. A name claimed by both
    /// a configured provider and a built-in routes to the configured one, which
    /// is the same winner {@link #all()} publishes.
    ///
    /// @param toolName the tool identifier to invoke, not null
    /// @param arguments arguments supplied by the agent, not null (may be empty)
    /// @param context the workflow state context, not null (may be empty)
    /// @return the provider's result, or a routing outcome when no single provider
    ///     claims the name; provider exceptions become failure results
    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        Objects.requireNonNull(toolName, "toolName must not be null");

        ToolProvider owner;
        try {
            owner = claimant(configured, toolName);
            if (owner == null) {
                owner = claimant(builtIns, toolName);
            }
        } catch (IllegalStateException e) {
            return ToolCallResult.of(
                    toolName, ToolCallStatus.CATALOG_ERROR, null, e.getMessage(), null);
        }

        if (owner == null) {
            return ToolCallResult.of(
                    toolName,
                    ToolCallStatus.UNKNOWN_TOOL,
                    null,
                    "Unknown tool '" + toolName + "' – no configured provider offers it",
                    null);
        }

        try {
            return owner.call(toolName, arguments, context);
        } catch (RuntimeException e) {
            return ToolCallResult.failure(
                    toolName, "Tool provider " + name(owner) + " failed: " + e.getMessage());
        }
    }

    /// Finds the single provider in one precedence band claiming a name.
    ///
    /// @param band providers of equal precedence, not null
    /// @param toolName the tool identifier being routed, not null
    /// @return the claiming provider, or null when none in this band claims it
    /// @throws IllegalStateException if two providers in the band claim the name
    private static ToolProvider claimant(List<ToolProvider> band, String toolName) {
        ToolProvider owner = null;
        for (ToolProvider provider : band) {
            if (claims(provider, toolName)) {
                if (owner != null) {
                    throw new IllegalStateException(duplicateMessage(toolName, owner, provider));
                }
                owner = provider;
            }
        }
        return owner;
    }

    /// Materializes the union of provider catalogs, rejecting duplicate names.
    ///
    /// Configured providers are collected first so that a built-in colliding
    /// with one of them can be dropped rather than fail the catalog. A provider
    /// that throws is logged and skipped; duplicate detection still applies
    /// across the providers that answered.
    ///
    /// @return unmodifiable union, never null
    /// @throws IllegalStateException if two providers of the same precedence
    ///     expose the same tool name
    private List<ToolDefinition> collectValidated() {
        return collect(ToolProvider::tools);
    }

    /// Materializes the union through one view of each provider's catalog.
    ///
    /// @param view how to read a provider – its live catalog, or the settled one
    ///     the constructor reads so that wiring starts nothing
    /// @return unmodifiable union, never null
    /// @throws IllegalStateException if two providers of the same precedence
    ///     expose the same tool name
    private List<ToolDefinition> collect(Function<ToolProvider, List<ToolDefinition>> view) {
        Map<String, ToolProvider> owners = new LinkedHashMap<>();
        List<ToolDefinition> union = new ArrayList<>();

        for (ToolProvider provider : configured) {
            for (ToolDefinition tool : offered(provider, view)) {
                ToolProvider previous = owners.putIfAbsent(tool.name(), provider);
                if (previous != null) {
                    throw new IllegalStateException(
                            duplicateMessage(tool.name(), previous, provider));
                }
                union.add(tool);
            }
        }

        for (ToolProvider provider : builtIns) {
            for (ToolDefinition tool : offered(provider, view)) {
                ToolProvider previous = owners.putIfAbsent(tool.name(), provider);
                if (unwrap(previous) instanceof BuiltInToolProvider) {
                    throw new IllegalStateException(
                            duplicateMessage(tool.name(), previous, provider));
                }
                if (previous != null) {
                    logger.warning(
                            "Built-in tool '"
                                    + tool.name()
                                    + "' is hidden by "
                                    + name(previous)
                                    + ", which publishes the same name – calls route to the"
                                    + " configured provider");
                    continue;
                }
                union.add(tool);
            }
        }
        return List.copyOf(union);
    }

    /// Reads one provider's catalog, treating a throwing provider as empty.
    private static List<ToolDefinition> offered(
            ToolProvider provider, Function<ToolProvider, List<ToolDefinition>> view) {
        try {
            return view.apply(provider);
        } catch (RuntimeException e) {
            logger.log(
                    Level.WARNING,
                    "Tool provider "
                            + name(provider)
                            + " failed to report its catalog and is skipped: "
                            + e.getMessage(),
                    e);
            return List.of();
        }
    }

    /// Asks a provider whether it owns a name, treating a throwing provider as not owning it.
    private static boolean claims(ToolProvider provider, String toolName) {
        try {
            return provider.provides(toolName);
        } catch (RuntimeException e) {
            logger.log(
                    Level.WARNING,
                    "Tool provider "
                            + name(provider)
                            + " failed while being asked for '"
                            + toolName
                            + "' and is skipped: "
                            + e.getMessage(),
                    e);
            return false;
        }
    }

    private static String duplicateMessage(
            String toolName, ToolProvider first, ToolProvider second) {
        return "Duplicate tool '"
                + toolName
                + "' from "
                + name(first)
                + " and "
                + name(second)
                + " – rename one in configuration";
    }

    /// Walks a decorator chain down to the provider that actually owns the tools.
    ///
    /// Precedence and diagnostics are properties of the source, not of whatever a
    /// deployment wrapped around it. Without this, one approval decorator in front of the
    /// built-ins would move them into the configured band and every duplicate-name error
    /// would name the decorator twice.
    ///
    /// @param provider a provider, possibly decorated, may be null
    /// @return the innermost provider, or null when given null
    public static ToolProvider unwrap(ToolProvider provider) {
        ToolProvider current = provider;
        // Bounded rather than while(true): a decorator whose delegate chain cycles is a
        // wiring bug, and hanging the router is a worse way to report it than giving up.
        for (int depth = 0; depth < MAX_DECORATOR_DEPTH; depth++) {
            if (!(current instanceof ToolProviderDecorator decorator)) {
                return current;
            }
            current = decorator.delegate();
        }
        logger.warning(
                "Tool provider decorator chain is deeper than "
                        + MAX_DECORATOR_DEPTH
                        + "; treating it as a configured provider");
        return current;
    }

    /// Names a provider for a diagnostic, looking through any decorators.
    private static String name(ToolProvider provider) {
        return unwrap(provider).getClass().getSimpleName();
    }
}
