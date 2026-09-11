package io.hensu.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
/// Two providers exposing the same tool name is a configuration error, because
/// the router cannot decide which one the agent meant. The constructor checks
/// for collisions, but that check is only best-effort: a provider whose catalog
/// is dynamic (tenant-scoped on the server, lazily started on the CLI) reports
/// nothing yet at construction time. The authoritative check therefore runs
/// again on every catalog materialization – {@link #all()} and {@link #get}
/// reject collisions, and {@link #call} reports
/// {@link ToolCallStatus#CATALOG_ERROR} – so a duplicate surfaces before any
/// tool executes and never reaches the model as an ordinary tool failure.
///
/// ### Thread Safety
/// @implNote Immutable; safe for concurrent use. Thread safety of the catalog
/// itself is each provider's responsibility.
///
/// @see ToolProvider for contributing a tool source
public final class ToolRouter implements ToolRegistry, ToolInvoker {

    private static final Logger logger = Logger.getLogger(ToolRouter.class.getName());

    private final List<ToolProvider> providers;

    /// Creates a router over the given providers.
    ///
    /// @param providers tool sources to compose, not null (may be empty)
    /// @throws NullPointerException if providers is null or contains null
    /// @throws IllegalStateException if two providers already expose the same tool name
    public ToolRouter(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
        collectValidated();
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
    /// @return the tool definition if exactly one provider exposes it, empty otherwise
    /// @throws NullPointerException if name is null
    /// @throws IllegalStateException if two providers expose this name
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
    /// @throws IllegalStateException if two providers expose the same tool name
    @Override
    public List<ToolDefinition> all() {
        return collectValidated();
    }

    /// Routes an invocation to the provider owning the tool.
    ///
    /// Never throws for a routing problem: an unroutable name yields
    /// {@link ToolCallStatus#UNKNOWN_TOOL} and a colliding name yields
    /// {@link ToolCallStatus#CATALOG_ERROR}, so the caller decides whether the
    /// model sees the outcome or the node simply fails.
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

        ToolProvider owner = null;
        for (ToolProvider provider : providers) {
            if (claims(provider, toolName)) {
                if (owner != null) {
                    return ToolCallResult.of(
                            toolName,
                            ToolCallStatus.CATALOG_ERROR,
                            null,
                            duplicateMessage(toolName, owner, provider),
                            null);
                }
                owner = provider;
            }
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
                    toolName,
                    "Tool provider "
                            + owner.getClass().getSimpleName()
                            + " failed: "
                            + e.getMessage());
        }
    }

    /// Materializes the union of provider catalogs, rejecting duplicate names.
    ///
    /// A provider that throws is logged and skipped; duplicate detection still
    /// applies across the providers that answered.
    ///
    /// @return unmodifiable union, never null
    /// @throws IllegalStateException if two providers expose the same tool name
    private List<ToolDefinition> collectValidated() {
        Map<String, ToolProvider> owners = new LinkedHashMap<>();
        List<ToolDefinition> union = new ArrayList<>();

        for (ToolProvider provider : providers) {
            List<ToolDefinition> offered;
            try {
                offered = provider.tools();
            } catch (RuntimeException e) {
                logger.log(
                        Level.WARNING,
                        "Tool provider "
                                + provider.getClass().getSimpleName()
                                + " failed to report its catalog and is skipped: "
                                + e.getMessage(),
                        e);
                continue;
            }

            for (ToolDefinition tool : offered) {
                ToolProvider previous = owners.putIfAbsent(tool.name(), provider);
                if (previous != null) {
                    throw new IllegalStateException(
                            duplicateMessage(tool.name(), previous, provider));
                }
                union.add(tool);
            }
        }
        return List.copyOf(union);
    }

    /// Asks a provider whether it owns a name, treating a throwing provider as not owning it.
    private static boolean claims(ToolProvider provider, String toolName) {
        try {
            return provider.provides(toolName);
        } catch (RuntimeException e) {
            logger.log(
                    Level.WARNING,
                    "Tool provider "
                            + provider.getClass().getSimpleName()
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
                + first.getClass().getSimpleName()
                + " and "
                + second.getClass().getSimpleName()
                + " – rename one in configuration";
    }
}
