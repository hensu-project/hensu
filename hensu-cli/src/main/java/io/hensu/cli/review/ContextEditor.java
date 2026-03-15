package io.hensu.cli.review;

import io.hensu.cli.ui.AnsiStyles;
import io.hensu.core.template.SimpleTemplateResolver;
import io.hensu.core.template.TemplateResolver;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// External-editor UI for modifying the resolved prompt during review backtrack.
///
/// Opens {@code $EDITOR} (defaults to {@code vim}) with a temporary file containing
/// the fully resolved prompt (template variables substituted with current context values).
/// The reviewer edits the prompt text directly — the edited version is returned as a
/// {@code _prompt_override} context entry so that {@link
/// io.hensu.core.execution.executor.StandardNodeExecutor} uses it verbatim instead of
/// re-resolving the template.
///
/// ### Thread Safety
/// **Not thread-safe.** Designed for single-threaded CLI use.
///
/// @see ReviewTerminal
class ContextEditor {

    private final TemplateResolver templateResolver = new SimpleTemplateResolver();
    private final PrintStream out;
    private final AnsiStyles styles;

    ContextEditor(PrintStream out, boolean useColor) {
        this.out = out;
        this.styles = AnsiStyles.of(useColor);
    }

    /// Opens an external editor to modify the resolved prompt.
    ///
    /// Resolves the prompt template with current context values, presents the result
    /// for editing, and returns a single-entry map with {@code _prompt_override} set
    /// to the edited text. Returns {@code null} if canceled or unchanged.
    ///
    /// @param context        current context variables for template resolution
    /// @param promptTemplate prompt template with {@code {var}} placeholders, may be null
    /// @param nodeId         the node being re-executed (for header display)
    /// @return map with {@code _prompt_override}, or {@code null} if canceled
    Map<String, Object> edit(Map<String, Object> context, String promptTemplate, String nodeId) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            println(styles.error("No prompt template available for this node."));
            return null;
        }

        String resolvedPrompt = templateResolver.resolve(promptTemplate, context);
        return editLoop(resolvedPrompt, nodeId);
    }

    private Map<String, Object> editLoop(String resolvedPrompt, String nodeId) {
        try {
            Path tempFile = Files.createTempFile("hensu-prompt-", ".md");

            String content = buildEditorContent(resolvedPrompt, nodeId);
            Files.writeString(tempFile, content);

            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) editor = "vim";

            ProcessBuilder pb = new ProcessBuilder(editor, tempFile.toString());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                println(styles.error("Editor exited with error code: " + exitCode));
                Files.deleteIfExists(tempFile);
                return null;
            }

            String edited = Files.readString(tempFile);
            Files.deleteIfExists(tempFile);

            String stripped = stripComments(edited);
            if (stripped.isBlank()) return null;

            if (stripped.equals(resolvedPrompt)) return null;

            return Map.of("_prompt_override", stripped);

        } catch (IOException | InterruptedException e) {
            println(styles.error("Error editing prompt: " + e.getMessage()));
            return null;
        }
    }

    private String buildEditorContent(String resolvedPrompt, String nodeId) {
        return "<!-- Hensu Prompt Editor\n"
                + "     Node: "
                + nodeId
                + "\n"
                + "\n"
                + "     Edit the prompt below. This is the text sent to the LLM.\n"
                + "     Delete all content to cancel.\n"
                + "     ─────────── EDIT PROMPT BELOW ─────────── -->\n"
                + resolvedPrompt
                + "\n";
    }

    private static String stripComments(String content) {
        return content.replaceAll("<!--[\\s\\S]*?-->", "").trim();
    }

    private void println(String text) {
        out.println(text);
    }
}
