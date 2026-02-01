package io.hensu.cli.ui;

/// ANSI text styling for CLI output with semantic color methods.
///
/// Provides text formatting methods that apply ANSI escape codes for terminal rendering.
/// All methods return styled strings; output handling is the caller's responsibility.
///
/// ### Usage
/// ```java
/// AnsiStyles styles = AnsiStyles.of(true);  // color enabled
/// System.out.println(styles.success("OK") + " - " + styles.bold("Done"));
/// ```
///
/// ### Thread Safety
/// @implNote **Thread-safe**. Instances are immutable after construction.
///
/// @see #of(boolean) factory method for creating instances
public final class AnsiStyles {

    private static final String BOLD = "\033[1m";
    private static final String GRAY = "\033[38;5;244m";
    private static final String DIM = "\033[38;5;241m";
    private static final String GREEN = "\033[0;32m";
    private static final String RED = "\033[38;5;167m";
    private static final String YELLOW = "\033[38;5;214m";
    private static final String BLUE = "\033[38;5;39m";
    private static final String RESET = "\033[0m";

    private final boolean useColor;

    private AnsiStyles(boolean useColor) {
        this.useColor = useColor;
    }

    /// Creates an AnsiStyles instance with specified color preference.
    ///
    /// @param useColor true to apply ANSI codes, false for plain text
    /// @return new instance, never null
    public static AnsiStyles of(boolean useColor) {
        return new AnsiStyles(useColor);
    }

    /// Returns whether color output is enabled.
    public boolean isColorEnabled() {
        return useColor;
    }

    // --- Internal ---

    private String style(String text, String code) {
        return useColor ? code + text + RESET : text;
    }

    // --- Text Formatting ---

    /// Applies bold formatting.
    public String bold(String text) {
        return style(text, BOLD);
    }

    /// Applies gray color for secondary elements.
    public String gray(String text) {
        return style(text, GRAY);
    }

    /// Applies dim color for less prominent text.
    public String dim(String text) {
        return style(text, DIM);
    }

    // --- Semantic Colors ---

    /// Applies green for success states.
    public String success(String text) {
        return style(text, GREEN);
    }

    /// Applies red for error states.
    public String error(String text) {
        return style(text, RED);
    }

    /// Applies yellow for warning states.
    public String warn(String text) {
        return style(text, YELLOW);
    }

    /// Applies blue for accent/highlight elements.
    public String accent(String text) {
        return style(text, BLUE);
    }

    // --- Conditional Colors ---

    /// Colors green on success, red on failure.
    public String successOrError(String text, boolean isSuccess) {
        return style(text, isSuccess ? GREEN : RED);
    }

    /// Colors green on success, yellow on warning.
    public String successOrWarn(String text, boolean isSuccess) {
        return style(text, isSuccess ? GREEN : YELLOW);
    }

    // --- Symbols ---

    /// Right arrow for transitions.
    public String arrow() {
        return style("→", BLUE);
    }

    /// Bullet point for lists.
    public String bullet() {
        return style("•", GRAY);
    }

    /// Checkmark for success.
    public String checkmark() {
        return style("✓", GREEN);
    }

    /// Cross mark for failure.
    public String crossmark() {
        return style("✗", RED);
    }

    // --- Box Drawing ---

    /// Box top-left corner: ┌─
    public String boxTop() {
        return style("┌─", DIM);
    }

    /// Box vertical line: │
    public String boxMid() {
        return style("│", DIM);
    }

    /// Box bottom-left corner: └─
    public String boxBottom() {
        return style("└─", DIM);
    }

    /// Full-width separator with top corner (62 chars).
    public String separatorTop() {
        return style("┌─────────────────────────────────────────────────────────────", DIM);
    }

    /// Full-width horizontal separator (62 chars).
    public String separatorMid() {
        return style(" ─────────────────────────────────────────────────────────────", DIM);
    }

    /// Full-width separator with bottom corner (62 chars).
    public String separatorBottom() {
        return style("└─────────────────────────────────────────────────────────────", DIM);
    }

    // --- Text Layout ---

    /// Centers text within specified width using space padding.
    public String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text + " ".repeat(width - text.length() - padding);
    }
}
