package io.hensu.cli.ui;

import java.util.regex.Pattern;

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

    // — Internal —————————————————————————————————————————————————————————————

    private static final Pattern ANSI_STRIP = Pattern.compile("\033\\[[;\\d]*m");

    private static String stripAnsi(String s) {
        return ANSI_STRIP.matcher(s).replaceAll("");
    }

    private String style(String text, String code) {
        return useColor ? code + text + RESET : text;
    }

    // — Text Formatting ——————————————————————————————————————————————————————

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

    // — Semantic Colors ——————————————————————————————————————————————————————

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

    // — Conditional Colors ———————————————————————————————————————————————————

    /// Colors green on success, red on failure.
    public String successOrError(String text, boolean isSuccess) {
        return style(text, isSuccess ? GREEN : RED);
    }

    /// Colors green on success, yellow on warning.
    public String successOrWarn(String text, boolean isSuccess) {
        return style(text, isSuccess ? GREEN : YELLOW);
    }

    // — Spinner ——————————————————————————————————————————————————————————————

    /// Braille spinner frames for animated progress indicators.
    ///
    /// Cycle through these with {@code SPINNER[tick % SPINNER.length]} on each
    /// refresh interval. The sequence gives a smooth rotation illusion.
    public static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /// Returns a spinner frame for the given tick count.
    ///
    /// @param tick monotonically increasing counter, any value
    /// @return single spinner character string, never null
    public String spinner(int tick) {
        return style(SPINNER[Math.abs(tick) % SPINNER.length], BLUE);
    }

    /// Returns a colored status dot for execution status display in {@code hensu ps}.
    ///
    /// @param status one of "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT",
    ///               or any other string (renders as dim circle), not null
    /// @return single dot character with appropriate color, never null
    public String statusDot(String status) {
        return switch (status) {
            case "RUNNING" -> style("●", BLUE);
            case "COMPLETED" -> style("●", GREEN);
            case "FAILED" -> style("●", RED);
            case "CANCELLED" -> style("○", GRAY);
            case "TIMED_OUT" -> style("●", YELLOW);
            default -> style("○", DIM);
        };
    }

    // — Symbols ——————————————————————————————————————————————————————————————

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

    // — Daemon status colors —————————————————————————————————————————————————

    /// Applies blue for actively-running states.
    public String running(String text) {
        return style(text, BLUE);
    }

    /// Applies yellow for pending or queued states.
    public String pending(String text) {
        return style(text, YELLOW);
    }

    /// Applies gray for canceled or inactive states.
    public String cancelled(String text) {
        return style(text, GRAY);
    }

    // — Box Drawing ——————————————————————————————————————————————————————————

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

    /// Full-width separator with top corner at the default width (80 columns).
    ///
    /// @return styled separator string, never null
    public String separatorTop() {
        return separatorTop(80);
    }

    /// Full-width horizontal separator at the default width (80 columns).
    ///
    /// @return styled separator string, never null
    public String separatorMid() {
        return separatorMid(80);
    }

    /// Full-width separator with bottom corner at the default width (80 columns).
    ///
    /// @return styled separator string, never null
    public String separatorBottom() {
        return separatorBottom(80);
    }

    /// Full-width separator with top corner sized to the given terminal width.
    ///
    /// @param width terminal width in columns, must be {@code > 1}
    /// @return styled separator string, never null
    public String separatorTop(int width) {
        return style("┌" + "─".repeat(Math.max(1, width - 1)), DIM);
    }

    /// Full-width horizontal separator sized to the given terminal width.
    ///
    /// @param width terminal width in columns, must be {@code > 0}
    /// @return styled separator string, never null
    public String separatorMid(int width) {
        return style(" " + "─".repeat(Math.max(0, width - 1)), DIM);
    }

    /// Full-width separator with bottom corner sized to the given terminal width.
    ///
    /// @param width terminal width in columns, must be {@code > 1}
    /// @return styled separator string, never null
    public String separatorBottom(int width) {
        return style("└" + "─".repeat(Math.max(1, width - 1)), DIM);
    }

    /// Box top line with an embedded label, filling the remainder to the given terminal width.
    ///
    /// ANSI escape codes in {@code styledLabel} are stripped for width measurement so the
    /// fill aligns correctly regardless of color state.
    ///
    /// @param styledLabel ANSI-styled label string, not null
    /// @param width       terminal width in columns, must be {@code > 1}
    /// @return styled box-top line with embedded label, never null
    public String boxTopWithLabel(String styledLabel, int width) {
        int rawLen = stripAnsi(styledLabel).length();
        int fill = Math.max(0, width - rawLen - 4); // 4 = "┌─ " (3) + " " trailing
        return style("┌─ ", DIM) + styledLabel + style(" " + "─".repeat(fill), DIM);
    }

    /// Box top line with an embedded label at the default width (80 columns).
    ///
    /// @param styledLabel ANSI-styled label string, not null
    /// @return styled box-top line with embedded label, never null
    public String boxTopWithLabel(String styledLabel) {
        return boxTopWithLabel(styledLabel, 80);
    }

    // — Text Layout ——————————————————————————————————————————————————————————

    /// Centers text within specified width using space padding.
    public String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text + " ".repeat(width - text.length() - padding);
    }
}
