package io.hensu.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsStoreTest {

    // — set ————————————————————————————————————————————————————————————————

    @Test
    void set_createsFileAndAppendsKey(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("credentials"));

        store.set("ANTHROPIC_API_KEY", "sk-ant-123");

        assertThat(Files.readAllLines(store.path(), StandardCharsets.UTF_8))
                .containsExactly("ANTHROPIC_API_KEY=sk-ant-123");
    }

    @Test
    void set_replacesExistingKey_preservingComments(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(
                file,
                "# comment\nANTHROPIC_API_KEY=old\nGOOGLE_API_KEY=goog\n",
                StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        store.set("ANTHROPIC_API_KEY", "new-value");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines)
                .contains("# comment", "ANTHROPIC_API_KEY=new-value", "GOOGLE_API_KEY=goog");
        assertThat(lines).doesNotContain("ANTHROPIC_API_KEY=old");
    }

    @Test
    void set_appendsNewKey_whenKeyAbsent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "EXISTING=value\n", StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        store.set("NEW_KEY", "new-value");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).contains("EXISTING=value", "NEW_KEY=new-value");
    }

    @Test
    void set_appliesRestrictedPermissions(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("credentials"));

        store.set("KEY", "value");

        if (store.path().getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(store.path()))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void set_createsParentDirectories(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("nested/dir/credentials"));

        store.set("KEY", "value");

        assertThat(Files.exists(store.path())).isTrue();
        assertThat(Files.readAllLines(store.path(), StandardCharsets.UTF_8))
                .containsExactly("KEY=value");
    }

    // — keys ———————————————————————————————————————————————————————————————

    @Test
    void keys_skipsCommentsAndBlanks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "# comment\n\nKEY_A=val1\nKEY_B=val2\n", StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        assertThat(store.keys()).containsExactly("KEY_A", "KEY_B");
    }

    @Test
    void keys_returnsEmptyWhenFileAbsent(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("nonexistent"));

        assertThat(store.keys()).isEmpty();
    }

    // — unset ——————————————————————————————————————————————————————————————

    @Test
    void unset_removesKey_preservingOthers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "# comment\nKEY_A=val1\nKEY_B=val2\n", StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        boolean removed = store.unset("KEY_A");

        assertThat(removed).isTrue();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).contains("# comment", "KEY_B=val2");
        assertThat(lines).doesNotContain("KEY_A=val1");
    }

    // — loadAll ————————————————————————————————————————————————————————————

    @Test
    void loadAll_parsesKeyValuePairs(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "# comment\nKEY_A=val1\nKEY_B=val2\n\n", StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        assertThat(store.loadAll())
                .containsEntry("KEY_A", "val1")
                .containsEntry("KEY_B", "val2")
                .hasSize(2);
    }

    @Test
    void loadAll_returnsEmptyWhenFileAbsent(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("nonexistent"));

        assertThat(store.loadAll()).isEmpty();
    }

    @Test
    void loadAll_skipsEntriesWithBlankValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "GOOD=value\nBAD=   \n", StandardCharsets.UTF_8);
        var store = new CredentialsStore(file);

        assertThat(store.loadAll()).containsEntry("GOOD", "value").hasSize(1);
    }

    // — concurrency ————————————————————————————————————————————————————————

    @Test
    void set_concurrentWriters_noLostUpdates(@TempDir Path dir) throws Exception {
        var store = new CredentialsStore(dir.resolve("credentials"));
        int writers = 8;
        var barrier = new CyclicBarrier(writers);

        try (var pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String key = "KEY_" + i;
                String value = "val_" + i;
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await();
                                    store.set(key, value);
                                    return null;
                                }));
            }
            for (var f : futures) {
                f.get();
            }
        }

        var all = store.loadAll();
        assertThat(all).hasSize(writers);
        for (int i = 0; i < writers; i++) {
            assertThat(all).containsEntry("KEY_" + i, "val_" + i);
        }
    }
}
