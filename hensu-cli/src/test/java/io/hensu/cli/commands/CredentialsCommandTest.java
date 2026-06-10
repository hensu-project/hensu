package io.hensu.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.cli.daemon.CredentialsStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsCommandTest extends BaseWorkflowCommandTest {

    // — Set ————————————————————————————————————————————————————————————————————

    @Test
    void set_createsFileAndAppendsKey_whenFileDoesNotExist(@TempDir Path tempDir) throws Exception {
        Path credFile = tempDir.resolve("credentials");
        var cmd = new CredentialsCommand.Set();
        injectField(cmd, "key", "ANTHROPIC_API_KEY");
        injectField(cmd, "stdin", true);
        injectField(cmd, "store", new CredentialsStore(credFile));

        withStdin("my-secret-value", cmd);

        assertThat(Files.readAllLines(credFile, StandardCharsets.UTF_8))
                .contains("ANTHROPIC_API_KEY=my-secret-value");
    }

    @Test
    void set_replacesExistingKey_preservingOtherLinesAndComments(@TempDir Path tempDir)
            throws Exception {
        Path credFile = tempDir.resolve("credentials");
        Files.writeString(
                credFile,
                "# comment\nANTHROPIC_API_KEY=old-value\nGOOGLE_API_KEY=google-value\n",
                StandardCharsets.UTF_8);

        var cmd = new CredentialsCommand.Set();
        injectField(cmd, "key", "ANTHROPIC_API_KEY");
        injectField(cmd, "stdin", true);
        injectField(cmd, "store", new CredentialsStore(credFile));

        withStdin("new-value", cmd);

        List<String> lines = Files.readAllLines(credFile, StandardCharsets.UTF_8);
        assertThat(lines).contains("# comment");
        assertThat(lines).contains("ANTHROPIC_API_KEY=new-value");
        assertThat(lines).contains("GOOGLE_API_KEY=google-value");
        assertThat(lines).doesNotContain("ANTHROPIC_API_KEY=old-value");
    }

    @Test
    void set_appendsNewKey_whenKeyNotPresentInExistingFile(@TempDir Path tempDir) throws Exception {
        Path credFile = tempDir.resolve("credentials");
        Files.writeString(credFile, "EXISTING_KEY=existing-value\n", StandardCharsets.UTF_8);

        var cmd = new CredentialsCommand.Set();
        injectField(cmd, "key", "NEW_KEY");
        injectField(cmd, "stdin", true);
        injectField(cmd, "store", new CredentialsStore(credFile));

        withStdin("new-value", cmd);

        List<String> lines = Files.readAllLines(credFile, StandardCharsets.UTF_8);
        assertThat(lines).contains("EXISTING_KEY=existing-value");
        assertThat(lines).contains("NEW_KEY=new-value");
    }

    // — Keys ———————————————————————————————————————————————————————————————————

    @Test
    void list_printsMaskedKeyNames_skippingCommentsAndBlanks(@TempDir Path tempDir)
            throws Exception {
        Path credFile = tempDir.resolve("credentials");
        Files.writeString(
                credFile,
                "# this is a comment\n\nANTHROPIC_API_KEY=secret1\nGOOGLE_API_KEY=secret2\n",
                StandardCharsets.UTF_8);

        var cmd = new CredentialsCommand.Keys();
        injectField(cmd, "store", new CredentialsStore(credFile));
        cmd.run();

        String out = outContent.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("ANTHROPIC_API_KEY");
        assertThat(out).contains("GOOGLE_API_KEY");
        assertThat(out).doesNotContain("secret1");
        assertThat(out).doesNotContain("secret2");
        assertThat(out).doesNotContain("this is a comment");
    }

    // — Unset ——————————————————————————————————————————————————————————————————

    @Test
    void unset_removesTargetKey_preservingCommentsAndOtherKeys(@TempDir Path tempDir)
            throws Exception {
        Path credFile = tempDir.resolve("credentials");
        Files.writeString(
                credFile,
                "# comment\nANTHROPIC_API_KEY=secret1\nGOOGLE_API_KEY=secret2\n",
                StandardCharsets.UTF_8);

        var cmd = new CredentialsCommand.Unset();
        injectField(cmd, "key", "ANTHROPIC_API_KEY");
        injectField(cmd, "store", new CredentialsStore(credFile));
        cmd.run();

        List<String> lines = Files.readAllLines(credFile, StandardCharsets.UTF_8);
        assertThat(lines).doesNotContain("ANTHROPIC_API_KEY=secret1");
        assertThat(lines).contains("GOOGLE_API_KEY=secret2");
        assertThat(lines).contains("# comment");
    }

    // — helpers ————————————————————————————————————————————————————————————————

    private void withStdin(String value, Runnable action) {
        var original = System.in;
        System.setIn(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
        try {
            action.run();
        } finally {
            System.setIn(original);
        }
    }
}
