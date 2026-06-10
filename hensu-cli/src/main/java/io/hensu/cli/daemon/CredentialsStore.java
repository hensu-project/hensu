package io.hensu.cli.daemon;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/// Manages API credentials stored as {@code KEY=VALUE} lines in a plain-text file.
///
/// Comments (lines starting with {@code #}) and blank lines are preserved across
/// mutations. The file is always written with {@code 0600} permissions on POSIX
/// filesystems.
///
/// @see DaemonPaths#credentials()
public final class CredentialsStore {

    private final Path file;
    private final Path lockFile;

    /// Creates a store backed by the given file path.
    ///
    /// @param file credential file location, not null; file and parent directories
    ///             are created on first write if absent
    public CredentialsStore(Path file) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
    }

    /// Creates a store backed by the default credentials path ({@code ~/.hensu/credentials}).
    ///
    /// @return store using {@link DaemonPaths#credentials()}, never null
    public static CredentialsStore ofDefaults() {
        return new CredentialsStore(DaemonPaths.credentials());
    }

    /// Sets or replaces a credential.
    ///
    /// If the key already exists, the line is replaced in-place preserving surrounding
    /// comments and ordering. Otherwise the entry is appended.
    ///
    /// @param key   credential name, not null or blank
    /// @param value credential value, not null
    /// @throws IOException if the file cannot be read or written
    public void set(String key, String value) throws IOException {
        Files.createDirectories(file.getParent());

        withFileLock(
                () -> {
                    List<String> lines =
                            Files.exists(file)
                                    ? new ArrayList<>(
                                            Files.readAllLines(file, StandardCharsets.UTF_8))
                                    : new ArrayList<>();

                    boolean found = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (!line.startsWith("#") && !line.isBlank()) {
                            int eq = line.indexOf('=');
                            if (eq > 0 && line.substring(0, eq).strip().equals(key)) {
                                lines.set(i, key + "=" + value);
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        lines.add(key + "=" + value);
                    }

                    Files.write(file, lines, StandardCharsets.UTF_8);
                    applyRestrictedPermissions();
                    return null;
                });
    }

    /// Returns the names of all configured credential keys.
    ///
    /// Comments and blank lines are skipped. Returns an empty list if the file
    /// does not exist or contains no entries.
    ///
    /// @return key names in file order, never null
    /// @throws IOException if the file exists but cannot be read
    public List<String> keys() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .filter(line -> line.indexOf('=') > 0)
                .map(line -> line.substring(0, line.indexOf('=')).strip())
                .toList();
    }

    /// Removes a credential key from the file.
    ///
    /// Comment lines and other entries are preserved. The file is left unchanged
    /// if the key is not present or the file does not exist.
    ///
    /// @param key credential name to remove, not null
    /// @return {@code true} if the key was found and removed
    /// @throws IOException if the file cannot be read or written
    public boolean unset(String key) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }

        return withFileLock(
                () -> {
                    List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
                    List<String> filtered =
                            original.stream()
                                    .filter(
                                            line -> {
                                                if (line.isBlank() || line.startsWith("#"))
                                                    return true;
                                                int eq = line.indexOf('=');
                                                return eq <= 0
                                                        || !line.substring(0, eq)
                                                                .strip()
                                                                .equals(key.strip());
                                            })
                                    .toList();

                    if (filtered.size() == original.size()) {
                        return false;
                    }

                    Files.write(file, filtered, StandardCharsets.UTF_8);
                    return true;
                });
    }

    /// Loads all credential key-value pairs from the file.
    ///
    /// Returns an empty map if the file does not exist. Comments and blank lines
    /// are skipped.
    ///
    /// @return credentials in file order, never null
    /// @throws IOException if the file exists but cannot be read
    public Map<String, String> loadAll() throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).strip();
                String v = line.substring(eq + 1).strip();
                if (!k.isBlank() && !v.isBlank()) {
                    result.put(k, v);
                }
            }
        }
        return result;
    }

    /// Returns the backing file path.
    ///
    /// @return credential file path, never null
    public Path path() {
        return file;
    }

    private static final ReentrantLock IN_PROCESS_LOCK = new ReentrantLock();

    private <T> T withFileLock(IOCallable<T> action) throws IOException {
        IN_PROCESS_LOCK.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (var channel =
                            FileChannel.open(
                                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                return action.call();
            }
        } finally {
            IN_PROCESS_LOCK.unlock();
        }
    }

    @FunctionalInterface
    private interface IOCallable<T> {
        T call() throws IOException;
    }

    private void applyRestrictedPermissions() {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception ignored) {
        }
    }
}
