package io.hensu.cli.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnsiStylesTest {

    @Test
    void shouldApplyBoldFormattingWhenColorEnabled() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.bold("test");

        assertThat(result).startsWith("\033[1m");
        assertThat(result).contains("test");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldReturnPlainTextWhenColorDisabled() {
        AnsiStyles styles = AnsiStyles.of(false);

        String result = styles.bold("test");

        assertThat(result).isEqualTo("test");
        assertThat(result).doesNotContain("\033[");
    }

    @Test
    void shouldApplySuccessColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.success("OK");

        assertThat(result).contains("\033[0;32m");
        assertThat(result).contains("OK");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldApplyErrorColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.error("FAIL");

        assertThat(result).contains("FAIL");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldApplyWarnColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.warn("WARNING");

        assertThat(result).contains("WARNING");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldApplyAccentColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.accent("highlight");

        assertThat(result).contains("highlight");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldApplyGrayColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.gray("secondary");

        assertThat(result).contains("secondary");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldApplyDimColor() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.dim("dimmed");

        assertThat(result).contains("dimmed");
        assertThat(result).endsWith("\033[0m");
    }

    @Test
    void shouldReturnSuccessColorWhenConditionTrue() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.successOrError("status", true);

        assertThat(result).contains("\033[0;32m");
    }

    @Test
    void shouldReturnErrorColorWhenConditionFalse() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.successOrError("status", false);

        assertThat(result).contains("\033[38;5;167m");
    }

    @Test
    void shouldReturnSuccessColorForSuccessOrWarnWhenTrue() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.successOrWarn("status", true);

        assertThat(result).contains("\033[0;32m");
    }

    @Test
    void shouldReturnWarnColorForSuccessOrWarnWhenFalse() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.successOrWarn("status", false);

        assertThat(result).contains("\033[38;5;214m");
    }

    @Test
    void shouldReturnStyledArrow() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.arrow();

        assertThat(result).contains("→");
    }

    @Test
    void shouldReturnStyledBullet() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.bullet();

        assertThat(result).contains("•");
    }

    @Test
    void shouldReturnStyledCheckmark() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.checkmark();

        assertThat(result).contains("✓");
    }

    @Test
    void shouldReturnStyledCrossmark() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.crossmark();

        assertThat(result).contains("✗");
    }

    @Test
    void shouldReturnBoxDrawingCharacters() {
        AnsiStyles styles = AnsiStyles.of(true);

        assertThat(styles.boxTop()).contains("┌─");
        assertThat(styles.boxMid()).contains("│");
        assertThat(styles.boxBottom()).contains("└─");
    }

    @Test
    void shouldReturnSeparators() {
        AnsiStyles styles = AnsiStyles.of(true);

        assertThat(styles.separatorTop()).contains("┌─");
        assertThat(styles.separatorMid()).contains("─");
        assertThat(styles.separatorBottom()).contains("└─");
    }

    @Test
    void shouldCenterTextWithPadding() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.center("test", 10);

        assertThat(result).hasSize(10);
        assertThat(result.trim()).isEqualTo("test");
    }

    @Test
    void shouldReturnTextUnchangedWhenLongerThanWidth() {
        AnsiStyles styles = AnsiStyles.of(true);

        String result = styles.center("longer text", 5);

        assertThat(result).isEqualTo("longer text");
    }

    @Test
    void shouldReturnColorEnabledStatus() {
        AnsiStyles colorEnabled = AnsiStyles.of(true);
        AnsiStyles colorDisabled = AnsiStyles.of(false);

        assertThat(colorEnabled.isColorEnabled()).isTrue();
        assertThat(colorDisabled.isColorEnabled()).isFalse();
    }
}
