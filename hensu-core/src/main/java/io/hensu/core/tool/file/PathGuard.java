package io.hensu.core.tool.file;

import io.hensu.core.execution.action.ProtectedConfigFiles;
import io.hensu.core.tool.ToolCallStatus;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/// Confines every file-tool path to one root, against an adversary that can
/// write into that root while the check is running.
///
/// The obvious sequence – resolve the argument, call `toRealPath()`, check the
/// result is inside the root, then open it – is a time-of-check to time-of-use
/// bug. Between the check and the open, anything able to write into the tree can
/// replace a path component with a symbolic link pointing anywhere. With a shell
/// rung running concurrently in the same working directory, that race is
/// trivially winnable, and the file tools deliberately run outside the sandbox
/// that would otherwise catch the escape.
///
/// The guard therefore never asks the filesystem to resolve a path for it:
///
/// 1. The argument is parsed first as a cheap filter – absolute paths, `..`
///    segments and empty names are refused before any syscall.
/// 2. The remainder is walked one segment at a time from the root, reading each
///    segment's attributes with {@link LinkOption#NOFOLLOW_LINKS}. A segment
///    that is itself a symbolic link is read, resolved against its own parent,
///    checked against the root, and the walk restarts from the root against the
///    resolved target – under {@link #MAX_LINK_DEPTH}, so a cycle terminates.
/// 3. The file is opened with `NOFOLLOW_LINKS`, so a final component swapped
///    for a symbolic link after step 2 fails the open rather than redirecting it.
/// 4. Containment is re-derived from the opened file before a byte moves, which
///    catches a directory component swapped between the walk and the open. A
///    write truncates only after that check passes, so an escape costs the file
///    it reached nothing.
///
/// ### Contracts
/// - **Precondition**: the root exists and is a directory when the guard is built
/// - **Postcondition**: every path returned is inside the root, with no symbolic
///   link anywhere in it that leaves the root
/// - **Invariant**: the guard never follows a link it has not itself checked
///
/// @implNote **Immutable after construction.** Holds only the resolved root;
/// safe to share across Virtual Threads.
/// @see FileToolProvider for the tools this protects
/// @see ProtectedConfigFiles for the files no write path may reach
final class PathGuard {

    /// Symbolic links a single resolution may traverse before the guard gives up.
    ///
    /// A link chain that exceeds this is either a cycle or an attempt to exhaust
    /// the walk, and neither deserves an unbounded number of syscalls.
    static final int MAX_LINK_DEPTH = 16;

    private final Path root;

    /// Creates a guard confining paths to a root.
    ///
    /// @param root the directory every path must stay inside, not null
    /// @throws FileToolException if the root does not exist or is not a directory
    PathGuard(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "the file tools are rooted at "
                            + root
                            + ", which cannot be read: "
                            + e.getMessage(),
                    e);
        }
        if (!Files.isDirectory(this.root)) {
            throw FileToolException.invalid(
                    "the file tools are rooted at " + this.root + ", which is not a directory");
        }
    }

    /// Returns the root every path is confined to.
    ///
    /// @return the resolved root, never null
    Path root() {
        return root;
    }

    /// Resolves an agent-supplied path against the root without following an
    /// unchecked link.
    ///
    /// The returned path may not exist. A write creates its target, so the walk
    /// stops at the first segment that is absent and appends the rest verbatim:
    /// nothing below a missing directory exists, so nothing there can be a link
    /// yet. Every segment that does exist has been checked.
    ///
    /// @param argument the path as the agent wrote it, may be null or blank
    /// @return the resolved path inside the root, never null
    /// @throws FileToolException if the argument is absolute, contains `..`, is
    ///     unusable as a path, escapes the root, or exceeds {@link #MAX_LINK_DEPTH}
    Path resolve(String argument) {
        return walk(parse(argument));
    }

    /// Parses the argument into segments, rejecting shapes that cannot be confined.
    private List<String> parse(String argument) {
        if (argument == null || argument.isBlank()) {
            throw FileToolException.invalid("path must not be empty");
        }
        Path candidate;
        try {
            candidate = Path.of(argument);
        } catch (InvalidPathException e) {
            throw FileToolException.invalid("'" + argument + "' is not a usable path");
        }
        if (candidate.isAbsolute() || argument.startsWith("~")) {
            throw FileToolException.invalid(
                    "path '"
                            + argument
                            + "' is absolute; file tools accept paths relative to "
                            + root);
        }
        List<String> segments = new ArrayList<>();
        for (Path element : candidate.normalize()) {
            String segment = element.toString();
            if ("..".equals(segment)) {
                throw FileToolException.invalid(
                        "path '" + argument + "' leaves the working directory " + root);
            }
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }

    /// Walks the segments from the root, resolving links under the depth bound.
    private Path walk(List<String> segments) {
        Deque<String> pending = new ArrayDeque<>(segments);
        Path current = root;
        int hops = 0;

        while (!pending.isEmpty()) {
            String segment = pending.removeFirst();
            Path candidate = current.resolve(segment);
            BasicFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                // Nothing exists here, so nothing below it exists either and no
                // remaining segment can be a link today. The write path creates
                // what is missing and opens the result with NOFOLLOW_LINKS, so a
                // link raced into place between here and the open fails the open.
                Path missing = candidate;
                for (String remaining : pending) {
                    missing = missing.resolve(remaining);
                }
                return missing;
            } catch (IOException e) {
                throw new FileToolException(
                        ToolCallStatus.FAILURE,
                        "could not read " + relative(candidate) + ": " + e.getMessage(),
                        e);
            }

            if (attributes.isSymbolicLink()) {
                if (++hops > MAX_LINK_DEPTH) {
                    throw FileToolException.invalid(
                            "path traverses more than "
                                    + MAX_LINK_DEPTH
                                    + " symbolic links, which is a cycle or an attack");
                }
                Path target = readLink(candidate, current);
                requireInside(target, relative(candidate) + " -> " + target);
                Deque<String> restarted = new ArrayDeque<>();
                for (Path part : root.relativize(target)) {
                    String text = part.toString();
                    if (!text.isEmpty()) {
                        restarted.addLast(text);
                    }
                }
                restarted.addAll(pending);
                pending = restarted;
                current = root;
                continue;
            }

            if (!pending.isEmpty() && !attributes.isDirectory()) {
                throw FileToolException.invalid("not a directory: " + relative(candidate));
            }
            current = candidate;
        }
        return current;
    }

    /// Reads a link and resolves it against the directory holding it.
    private Path readLink(Path link, Path parent) {
        try {
            Path target = Files.readSymbolicLink(link);
            return (target.isAbsolute() ? target : parent.resolve(target)).normalize();
        } catch (IOException e) {
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "could not read the symbolic link " + relative(link) + ": " + e.getMessage(),
                    e);
        }
    }

    /// Opens a file for reading and re-derives containment from what was opened.
    ///
    /// @param path a path already returned by {@link #resolve}, not null
    /// @return the open stream, never null; the caller closes it
    /// @throws FileToolException if the file is missing, unreadable, or no longer
    ///     resolves inside the root
    @SuppressWarnings("resource") // hands the stream to the caller; verifyOpened closes on refusal
    InputStream openForRead(Path path) {
        InputStream stream;
        try {
            stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw FileToolException.invalid("no such file: " + relative(path));
        } catch (IOException e) {
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "could not open " + relative(path) + ": " + e.getMessage(),
                    e);
        }
        return verifyOpened(stream, path, false);
    }

    /// Opens a file for writing, truncating it only once containment holds for
    /// the file that was actually opened.
    ///
    /// Opening with `TRUNCATE_EXISTING` would order the two steps the wrong way
    /// round: a directory component swapped between the walk and the open sends
    /// the open to a file outside the root, and the length would already be zero
    /// by the time the escape was noticed. The file is therefore opened intact,
    /// checked, and only then truncated – so a call that escaped leaves its
    /// target byte for byte as it found it.
    ///
    /// A target that has to be created is created with `CREATE_NEW` and removed
    /// again if the check then fails, which keeps the same promise for a path
    /// that did not exist: an escaped write leaves nothing behind outside the
    /// root.
    ///
    /// @param path a path already returned by {@link #resolve}, not null
    /// @param create whether a missing target is created rather than refused
    /// @return the open stream positioned at the start of an empty file, never
    ///     null; the caller closes it
    /// @throws FileToolException if the target is a symbolic link, is missing and
    ///     `create` is false, is a protected configuration file, cannot be
    ///     written, or no longer resolves inside the root
    OutputStream openForWrite(Path path, boolean create) {
        requireWritable(path);
        boolean created = false;
        FileChannel channel;
        try {
            channel = open(path, StandardOpenOption.WRITE);
        } catch (NoSuchFileException missing) {
            if (!create) {
                throw FileToolException.invalid("no such file: " + relative(path));
            }
            try {
                channel = open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            } catch (NoSuchFileException absentParent) {
                // The directory the caller made for this write is gone again.
                throw FileToolException.invalid(
                        "no such directory to write " + relative(path) + " into");
            }
            created = true;
        }
        try {
            verifyOpened(channel, path, true);
            channel.truncate(0);
            return Channels.newOutputStream(channel);
        } catch (IOException e) {
            closeQuietly(channel);
            deleteIfCreated(path, created);
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "could not write " + relative(path) + ": " + e.getMessage(),
                    e);
        } catch (FileToolException e) {
            // verifyOpened has closed the channel; an escaped target we created
            // must not survive the refusal.
            deleteIfCreated(path, created);
            throw e;
        }
    }

    /// Opens a channel without following a link in the final component.
    private FileChannel open(Path path, OpenOption... options) throws NoSuchFileException {
        OpenOption[] effective = new OpenOption[options.length + 1];
        System.arraycopy(options, 0, effective, 0, options.length);
        effective[options.length] = LinkOption.NOFOLLOW_LINKS;
        try {
            return FileChannel.open(path, effective);
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "could not write " + relative(path) + ": " + e.getMessage(),
                    e);
        }
    }

    /// Removes a target this call brought into existence before it was refused.
    private void deleteIfCreated(Path path, boolean created) {
        if (!created) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The caller is already failing, and an empty file is the smallest
            // trace a refused write can leave.
        }
    }

    /// Refuses a write that would rewrite the catalog deciding what may run.
    ///
    /// @param path the resolved target, not null
    /// @throws FileToolException if the path names a protected configuration file
    void requireWritable(Path path) {
        if (ProtectedConfigFiles.isProtected(root, path)) {
            throw FileToolException.invalid(
                    relative(path)
                            + " decides what this deployment may execute and is never writable"
                            + " from a tool; change it yourself if the run needs a capability"
                            + " it does not have");
        }
    }

    /// Re-derives containment after an open, closing the resource if it escaped.
    ///
    /// The write deny list is re-applied here as well as before the open,
    /// because the path that was checked and the file that was opened are only
    /// the same object if nothing moved in between – which is the whole reason
    /// this method exists.
    private <T extends Closeable> T verifyOpened(T resource, Path path, boolean forWrite) {
        try {
            Path real = path.toRealPath();
            requireInside(real, relative(path));
            if (forWrite) {
                requireWritable(real);
            }
        } catch (IOException e) {
            closeQuietly(resource);
            throw new FileToolException(
                    ToolCallStatus.FAILURE,
                    "could not confirm " + relative(path) + " after opening it: " + e.getMessage(),
                    e);
        } catch (FileToolException e) {
            closeQuietly(resource);
            throw e;
        }
        return resource;
    }

    private static void closeQuietly(Closeable resource) {
        try {
            resource.close();
        } catch (IOException ignored) {
            // The caller is already failing; a close error adds nothing.
        }
    }

    /// Fails unless a path sits inside the root.
    private void requireInside(Path path, String described) {
        if (!path.startsWith(root)) {
            throw FileToolException.invalid(described + " leaves the working directory " + root);
        }
    }

    /// Renders a path the way the agent wrote it, so errors never leak the host layout.
    ///
    /// @param path any path, not null
    /// @return the path relative to the root when it is inside, its own text otherwise
    String relative(Path path) {
        return path.startsWith(root) ? root.relativize(path).toString() : path.toString();
    }
}
