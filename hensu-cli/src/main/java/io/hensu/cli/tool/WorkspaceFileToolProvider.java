package io.hensu.cli.tool;

import io.hensu.core.tool.BuiltInToolProvider;
import io.hensu.core.tool.PreviewCapable;
import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolDefinition;
import io.hensu.core.tool.ToolPreview;
import io.hensu.core.tool.file.FileToolProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Publishes the engine's built-in file tools, rooted at the run's working directory.
///
/// {@link FileToolProvider} is confined at construction, which is the property that makes
/// its containment checkable — the root is a field, not an argument a call could move.
/// The CLI, though, does not know the working directory when CDI wires its beans: it is
/// chosen by `hensu run -d <dir>` and reaches {@link CommandCatalog} as the run starts.
/// This bean is the join between those two facts. It is a discovered singleton with no
/// root of its own, and it builds — and rebuilds — the confined provider whenever the
/// catalog's directory changes.
///
/// It stays a {@link BuiltInToolProvider}, so the router's precedence carve-out still
/// applies: a deployment that installs a filesystem MCP server publishing `read_file`
/// takes the name, with a warning, rather than failing to start.
///
/// ### Contracts
/// - **Invariant**: every call is served by a provider rooted at the catalog's current
///   working directory, never at the directory the JVM happened to start in
/// - **Postcondition**: the root changes only between runs, never during one
///
/// @see FileToolProvider for the tools and their containment
/// @see CommandCatalog for where the working directory is set
@Singleton
public class WorkspaceFileToolProvider implements BuiltInToolProvider, PreviewCapable {

    private final CommandCatalog catalog;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Path rootedAt;
    private volatile FileToolProvider delegate;

    @Inject
    public WorkspaceFileToolProvider(CommandCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /// Returns the directory the tools are currently confined to.
    ///
    /// @return the resolved root, never null
    public Path root() {
        return delegate().root();
    }

    @Override
    public List<ToolDefinition> tools() {
        return delegate().tools();
    }

    @Override
    public boolean provides(String toolName) {
        return delegate().provides(toolName);
    }

    @Override
    public ToolCallResult call(
            String toolName, Map<String, Object> arguments, Map<String, Object> context) {
        return delegate().call(toolName, arguments, context);
    }

    @Override
    public ToolPreview preview(String toolName, Map<String, Object> arguments) {
        return delegate().preview(toolName, arguments);
    }

    /// Returns a provider rooted at the catalog's working directory, building one if the
    /// directory has changed since the last call.
    ///
    /// Reading the field twice around the lock is deliberate: the common path is a run
    /// that set its directory once, where this is a volatile read and a comparison.
    private FileToolProvider delegate() {
        Path root = catalog.workingDirectory();
        FileToolProvider current = delegate;
        if (current != null && root.equals(rootedAt)) {
            return current;
        }
        lock.lock();
        try {
            if (delegate == null || !root.equals(rootedAt)) {
                delegate = new FileToolProvider(root);
                rootedAt = root;
            }
            return delegate;
        } finally {
            lock.unlock();
        }
    }
}
