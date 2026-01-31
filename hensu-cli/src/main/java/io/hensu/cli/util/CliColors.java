package io.hensu.cli.util;

/// Universal minimalist color palette for CLI output. Works on both light and dark terminal themes.
public final class CliColors {

    private CliColors() {}

    // Focus
    public static final String BOLD = "\033[1m";

    // Borders
    public static final String GRAY = "\033[38;5;244m";

    // Labels
    //    public static final String DIM = "\033[38;5;239m";
    public static final String DIM = "\033[38;5;241m";

    // Success
    public static final String GREEN = "\033[0;32m";
    //    public static final String GREEN = "\033[38;5;114m";

    // Errors/failures
    public static final String RED = "\033[38;5;167m";

    // Warnings
    public static final String YELLOW = "\033[38;5;214m";
    //    public static final String YELLOW = "\033[38;5;214m";
    //    public static final String YELLOW = "\033[38;5;223m";

    // Accent
    //        public static final String PURPLE = "\033[38;5;141m";
    //    public static final String PURPLE = "\033[0;35m";
    public static final String BLUE = "\033[38;5;39m";

    // No color
    public static final String NC = "\033[0m";

    // --- Convenience methods ---

    public static String bold(String text) {
        return BOLD + text + NC;
    }

    public static String gray(String text) {
        return GRAY + text + NC;
    }

    public static String success(String text) {
        return GREEN + text + NC;
    }

    public static String error(String text) {
        return RED + text + NC;
    }

    public static String accent(String text) {
        return BLUE + text + NC;
    }

    public static String warn(String text) {
        return YELLOW + text + NC;
    }

    // --- UI Elements ---

    public static String successMark() {
        return GREEN + "✓" + NC;
    }

    public static String failMark() {
        return RED + "✗" + NC;
    }

    public static String arrow() {
        return BLUE + "→" + NC;
    }

    public static String bullet() {
        return GRAY + "•" + NC;
    }

    // --- Box drawing ---

    public static String boxTop() {
        return GRAY + "┌─" + NC;
    }

    public static String boxMid() {
        return GRAY + "│" + NC;
    }

    public static String boxBottom() {
        return GRAY + "└─" + NC;
    }

    public static String separatorTop() {
        return DIM + "┌─────────────────────────────────────────────────────────────" + NC;
    }

    public static String separatorMid() {
        return DIM + " ─────────────────────────────────────────────────────────────" + NC;
    }

    public static String separatorBottom() {
        return DIM + "└─────────────────────────────────────────────────────────────" + NC;
    }
}
