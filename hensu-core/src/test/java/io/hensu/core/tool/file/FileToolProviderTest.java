package io.hensu.core.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.core.tool.ToolCallResult;
import io.hensu.core.tool.ToolCallStatus;
import io.hensu.core.tool.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileToolProviderTest {

    @TempDir Path root;

    private FileToolProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\n");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "class App { int answer = 42; }\n");
        provider = new FileToolProvider(root);
    }

    private ToolCallResult call(String tool, Object... pairs) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            arguments.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return provider.call(tool, arguments, new HashMap<>());
    }

    @Nested
    // Every openFor* call below asserts a refusal, so the guard has already closed
    // what it opened and there is no resource for the caller to close. A test here
    // that keeps an open stream must close it itself.
    @SuppressWarnings("resource")
    class Containment {

        @Test
        void shouldRefuseARelativePathThatClimbsOutOfTheRoot() {
            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "../secrets.txt");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("leaves the working directory");
        }

        @Test
        void shouldRefuseAnAbsolutePath() {
            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "/etc/passwd");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("absolute");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldRefuseASymbolicLinkPointingOutOfTheTree(@TempDir Path outside)
                throws IOException {
            Path secret = Files.writeString(outside.resolve("secret.txt"), "classified");
            Files.createSymbolicLink(root.resolve("escape.txt"), secret);

            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "escape.txt");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("leaves the working directory");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldFollowASymbolicLinkThatStaysInsideTheTree() throws IOException {
            Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("notes.txt"));

            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "alias.txt");

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).contains("alpha");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldNotEscapeWhenALinkIsSwappedInAfterTheArgumentIsValidated(@TempDir Path outside)
                throws IOException {
            // The check-then-open window the segment walk exists to close: the
            // argument names a plain file when it is validated and a link out of
            // the tree by the time the bytes are read.
            Path secret = Files.writeString(outside.resolve("secret.txt"), "classified");
            Path target = root.resolve("racy.txt");
            Files.writeString(target, "harmless");

            PathGuard guard = new PathGuard(root);
            Path resolved = guard.resolve("racy.txt");
            assertThat(resolved).isEqualTo(target);

            Files.delete(target);
            Files.createSymbolicLink(target, secret);

            assertThatThrownBy(() -> guard.openForRead(resolved))
                    .isInstanceOf(FileToolException.class);
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldLeaveAnEscapedTargetIntactWhenADirectoryIsSwappedBeforeTheWrite(
                @TempDir Path outside) throws IOException {
            // Refusing the write is not enough: opening with TRUNCATE_EXISTING
            // would empty the file the escape reached before the guard noticed it
            // had left the root. The length must survive the refusal.
            Path victim = Files.writeString(outside.resolve("notes.txt"), "irreplaceable");
            Files.createDirectory(root.resolve("inner"));

            PathGuard guard = new PathGuard(root);
            Path resolved = guard.resolve("inner/notes.txt");

            // The directory component the walk checked becomes a link out of the
            // tree, so the open lands on the victim and only the re-derivation
            // after it can tell.
            Files.delete(root.resolve("inner"));
            Files.createSymbolicLink(root.resolve("inner"), outside);

            assertThatThrownBy(() -> guard.openForWrite(resolved, true))
                    .isInstanceOf(FileToolException.class)
                    .hasMessageContaining("leaves the working directory");
            assertThat(Files.readString(victim)).isEqualTo("irreplaceable");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldLeaveNoFileBehindWhenACreatingWriteEscapes(@TempDir Path outside)
                throws IOException {
            // Same window, but the target does not exist yet: the create must be
            // undone, or a refused write litters a directory outside the root.
            Files.createDirectory(root.resolve("inner"));

            PathGuard guard = new PathGuard(root);
            Path resolved = guard.resolve("inner/fresh.txt");

            Files.delete(root.resolve("inner"));
            Files.createSymbolicLink(root.resolve("inner"), outside);

            assertThatThrownBy(() -> guard.openForWrite(resolved, true))
                    .isInstanceOf(FileToolException.class);
            assertThat(outside.resolve("fresh.txt")).doesNotExist();
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldRefuseALinkCycleRatherThanWalkForever() throws IOException {
            Files.createSymbolicLink(root.resolve("a"), root.resolve("b"));
            Files.createSymbolicLink(root.resolve("b"), root.resolve("a"));

            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "a");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("symbolic links");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldNotSearchThroughALinkedDirectoryThatLeavesTheTree(@TempDir Path outside)
                throws IOException {
            Files.writeString(outside.resolve("leak.txt"), "answer = 42");
            Files.createSymbolicLink(root.resolve("linked"), outside);

            ToolCallResult result = call(FileToolProvider.GREP, "pattern", "answer");

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).doesNotContain("leak.txt");
            assertThat(result.output()).contains("App.java");
        }
    }

    @Nested
    class CatalogImmunity {

        @Test
        void shouldRefuseToWriteTheCommandCatalog() throws IOException {
            Files.writeString(root.resolve("commands.yaml"), "commands: {}\n");

            ToolCallResult result =
                    call(
                            FileToolProvider.WRITE_FILE,
                            "path",
                            "commands.yaml",
                            "content",
                            "commands:\n  own-everything:\n    rung: true\n");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(Files.readString(root.resolve("commands.yaml"))).isEqualTo("commands: {}\n");
        }

        @Test
        void shouldRefuseToEditTheMcpCatalog() throws IOException {
            Files.writeString(root.resolve("mcp.yaml"), "servers:\n  fs:\n    command: [\"x\"]\n");

            ToolCallResult result =
                    call(
                            FileToolProvider.EDIT_FILE,
                            "path",
                            "mcp.yaml",
                            "old",
                            "fs",
                            "new",
                            "anything");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("never writable");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void shouldRefuseToWriteTheCatalogThroughAnAliasThatIsNotItsName() throws IOException {
            // The deny list matches names under the root, so it only holds because
            // the segment walk canonicalises an alias before the name is checked.
            // Drop the walk's link resolution and this write lands on the catalog.
            Files.writeString(root.resolve("commands.yaml"), "commands: {}\n");
            Files.createSymbolicLink(root.resolve("harmless.yaml"), root.resolve("commands.yaml"));
            assertThat(new PathGuard(root).resolve("harmless.yaml"))
                    .isEqualTo(root.resolve("commands.yaml"));

            ToolCallResult result =
                    call(
                            FileToolProvider.WRITE_FILE,
                            "path",
                            "harmless.yaml",
                            "content",
                            "commands:\n  own-everything:\n    rung: true\n");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(Files.readString(root.resolve("commands.yaml"))).isEqualTo("commands: {}\n");
        }

        @Test
        void shouldStillReadTheCatalogSoAnAgentCanExplainWhatItLacks() throws IOException {
            Files.writeString(root.resolve("commands.yaml"), "commands: {}\n");

            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "commands.yaml");

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(result.output()).contains("commands:");
        }
    }

    @Nested
    class Reading {

        @Test
        void shouldReturnTheLineRangeTheCallNames() {
            ToolCallResult result =
                    call(FileToolProvider.READ_FILE, "path", "notes.txt", "offset", 2, "limit", 1);

            assertThat(result.output()).startsWith("beta\n");
            // The window ends before the file does, so the agent is told where to resume.
            assertThat(result.output()).contains("continue with offset 3");
        }

        @Test
        void shouldMarkTruncationAndNameTheNextOffset() throws IOException {
            StringBuilder long_ = new StringBuilder();
            for (int i = 1; i <= 10; i++) {
                long_.append("line ").append(i).append('\n');
            }
            Files.writeString(root.resolve("long.txt"), long_.toString());

            ToolCallResult result =
                    call(FileToolProvider.READ_FILE, "path", "long.txt", "limit", 3);

            assertThat(result.output()).contains("line 3").doesNotContain("line 4");
            assertThat(result.output()).contains("continue with offset 4");
        }

        @Test
        void shouldNotClaimTruncationWhenTheFileEndsExactlyAtTheLimit() {
            ToolCallResult result =
                    call(FileToolProvider.READ_FILE, "path", "notes.txt", "limit", 3);

            assertThat(result.output()).isEqualTo("alpha\nbeta\ngamma\n");
        }

        @Test
        void shouldReportAMissingFileAsAnArgumentProblem() {
            ToolCallResult result = call(FileToolProvider.READ_FILE, "path", "nowhere/at/all.txt");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("no such file");
        }
    }

    @Nested
    class Searching {

        @Test
        void shouldListEntriesWithDirectoriesMarked() {
            ToolCallResult result = call(FileToolProvider.LIST_DIR, "path", ".");

            assertThat(result.output()).contains("src/");
            assertThat(result.output()).contains("notes.txt");
        }

        @Test
        void shouldMatchGlobsAgainstPathsRelativeToTheRoot() {
            ToolCallResult result = call(FileToolProvider.GLOB, "pattern", "src/**/*.java");

            assertThat(result.output()).isEqualTo("src/main/App.java");
        }

        @Test
        void shouldReportGrepMatchesAsPathLineText() {
            ToolCallResult result = call(FileToolProvider.GREP, "pattern", "answer = \\d+");

            assertThat(result.output())
                    .isEqualTo("src/main/App.java:1:class App { int answer = 42; }");
        }

        @Test
        void shouldTruncateAtTheMatchBudgetRatherThanFloodTheContextWindow() throws IOException {
            Path many = Files.createDirectory(root.resolve("many"));
            for (int i = 0; i <= FileToolProvider.MAX_MATCHES; i++) {
                Files.writeString(many.resolve("file" + i + ".txt"), "needle\n");
            }

            ToolCallResult result = call(FileToolProvider.GREP, "pattern", "needle");

            assertThat(result.output().lines().filter(l -> l.contains("needle")).count())
                    .isEqualTo(FileToolProvider.MAX_MATCHES);
            assertThat(result.output()).contains("[truncated:");
        }

        @Test
        void shouldSkipBinaryFiles() throws IOException {
            Files.write(root.resolve("blob.bin"), new byte[] {'n', 'e', 'e', 'd', 0, 'l', 'e'});

            ToolCallResult result = call(FileToolProvider.GREP, "pattern", "need");

            assertThat(result.output()).doesNotContain("blob.bin");
        }

        @Test
        void shouldRejectAnUnusableRegularExpression() {
            ToolCallResult result = call(FileToolProvider.GREP, "pattern", "([unclosed");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("regular expression");
        }
    }

    @Nested
    class Writing {

        @Test
        void shouldCreateMissingParentDirectories() throws IOException {
            ToolCallResult result =
                    call(
                            FileToolProvider.WRITE_FILE,
                            "path",
                            "docs/deep/notes.md",
                            "content",
                            "# Title\n");

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(Files.readString(root.resolve("docs/deep/notes.md"))).isEqualTo("# Title\n");
        }

        @Test
        void shouldReplaceExactlyOnePassage() throws IOException {
            ToolCallResult result =
                    call(
                            FileToolProvider.EDIT_FILE,
                            "path",
                            "notes.txt",
                            "old",
                            "beta",
                            "new",
                            "BETA");

            assertThat(result.status()).isEqualTo(ToolCallStatus.SUCCESS);
            assertThat(Files.readString(root.resolve("notes.txt")))
                    .isEqualTo("alpha\nBETA\ngamma\n");
        }

        @Test
        void shouldRefuseAnEditWhoseOldTextIsAmbiguous() throws IOException {
            Files.writeString(root.resolve("repeat.txt"), "same\nsame\n");

            ToolCallResult result =
                    call(
                            FileToolProvider.EDIT_FILE,
                            "path",
                            "repeat.txt",
                            "old",
                            "same",
                            "new",
                            "changed");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(result.error()).contains("more than once");
            assertThat(Files.readString(root.resolve("repeat.txt"))).isEqualTo("same\nsame\n");
        }

        @Test
        void shouldRefuseAnEditWhoseOldTextIsAbsentRatherThanGuess() throws IOException {
            ToolCallResult result =
                    call(
                            FileToolProvider.EDIT_FILE,
                            "path",
                            "notes.txt",
                            "old",
                            "delta",
                            "new",
                            "DELTA");

            assertThat(result.status()).isEqualTo(ToolCallStatus.VALIDATION_FAILED);
            assertThat(Files.readString(root.resolve("notes.txt")))
                    .isEqualTo("alpha\nbeta\ngamma\n");
        }
    }

    @Nested
    class Catalog {

        @Test
        void shouldClaimEveryNameItPublishesAndNothingElse() {
            // provides() is a switch and tools() is a list, both written by hand in
            // one class. A name added to one and not the other is listed by the
            // router's catalog and routed to nobody.
            assertThat(provider.tools())
                    .extracting(ToolDefinition::name)
                    .allSatisfy(name -> assertThat(provider.provides(name)).isTrue());
            assertThat(provider.provides("run-tests")).isFalse();
        }

        @Test
        void shouldDescribeACallWithoutMakingIt() {
            var preview = provider.preview(FileToolProvider.READ_FILE, Map.of("path", "notes.txt"));

            assertThat(preview.summary()).isEqualTo("read_file notes.txt");
            assertThat(preview.argv()).isEmpty();
            assertThat(preview.unattendedSafe()).isTrue();
        }

        @Test
        void shouldReportAnUnknownNameRatherThanThrow() {
            ToolCallResult result = call("delete_everything", "path", "notes.txt");

            assertThat(result.status()).isEqualTo(ToolCallStatus.UNKNOWN_TOOL);
        }
    }
}
