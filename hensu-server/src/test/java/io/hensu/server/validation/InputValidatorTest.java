package io.hensu.server.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InputValidatorTest {

    // ———————————————— isSafeId ————————————————

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void isSafeIdShouldRejectNullBlank(String value) {
        assertThat(InputValidator.isSafeId(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {".starts-with-dot", "-starts-with-dash", "_starts-with-underscore"})
    void isSafeIdShouldRejectBadStart(String value) {
        assertThat(InputValidator.isSafeId(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has spaces", "has/slash", "has:colon", "has@at", "has\ttab"})
    void isSafeIdShouldRejectSpecialChars(String value) {
        assertThat(InputValidator.isSafeId(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abc", "my-workflow", "v1.0.0", "node_1", "A2.b-c_D"})
    void isSafeIdShouldAcceptValidIds(String value) {
        assertThat(InputValidator.isSafeId(value)).isTrue();
    }

    @Test
    void isSafeIdShouldRejectOverlong() {
        String tooLong = "a" + "b".repeat(255);
        assertThat(tooLong).hasSize(256);
        assertThat(InputValidator.isSafeId(tooLong)).isFalse();
    }

    @Test
    void isSafeIdShouldAcceptMaxLength() {
        String maxLength = "a" + "b".repeat(254);
        assertThat(maxLength).hasSize(255);
        assertThat(InputValidator.isSafeId(maxLength)).isTrue();
    }

    // ———————————————— containsDangerousChars ————————————————

    @Test
    void containsDangerousCharsShouldReturnFalseForNull() {
        assertThat(InputValidator.containsDangerousChars(null)).isFalse();
    }

    @Test
    void containsDangerousCharsShouldReturnFalseForCleanText() {
        assertThat(InputValidator.containsDangerousChars("Hello, world!")).isFalse();
    }

    @Test
    void containsDangerousCharsShouldAllowTabNewlineCr() {
        assertThat(InputValidator.containsDangerousChars("line1\nline2\ttabbed\rreturn")).isFalse();
    }

    @Test
    void containsDangerousCharsShouldDetectNullByte() {
        assertThat(InputValidator.containsDangerousChars("before\0after")).isTrue();
    }

    @Test
    void containsDangerousCharsShouldDetectBellChar() {
        assertThat(InputValidator.containsDangerousChars("alert\u0007bell")).isTrue();
    }

    @Test
    void containsDangerousCharsShouldDetectBackspace() {
        assertThat(InputValidator.containsDangerousChars("back\u0008space")).isTrue();
    }

    @Test
    void containsDangerousCharsShouldDetectFormFeed() {
        assertThat(InputValidator.containsDangerousChars("form\u000Cfeed")).isTrue();
    }

    @Test
    void containsDangerousCharsShouldDetectDelete() {
        assertThat(InputValidator.containsDangerousChars("del\u007F")).isTrue();
    }

    @Test
    void containsDangerousCharsShouldDetectEscape() {
        assertThat(InputValidator.containsDangerousChars("esc\u001Bchar")).isTrue();
    }

    // ———————————————— exceedsSizeLimit ————————————————

    @Test
    void exceedsSizeLimitShouldReturnFalseForNull() {
        assertThat(InputValidator.exceedsSizeLimit(null, 100)).isFalse();
    }

    @Test
    void exceedsSizeLimitShouldReturnFalseWhenUnder() {
        assertThat(InputValidator.exceedsSizeLimit("abc", 10)).isFalse();
    }

    @Test
    void exceedsSizeLimitShouldReturnFalseAtExactLimit() {
        assertThat(InputValidator.exceedsSizeLimit("abc", 3)).isFalse();
    }

    @Test
    void exceedsSizeLimitShouldReturnTrueWhenOver() {
        assertThat(InputValidator.exceedsSizeLimit("abcd", 3)).isTrue();
    }

    @Test
    void exceedsSizeLimitShouldAccountForMultibyteChars() {
        // Euro sign is 3 bytes in UTF-8
        assertThat(InputValidator.exceedsSizeLimit("€", 2)).isTrue();
        assertThat(InputValidator.exceedsSizeLimit("€", 3)).isFalse();
    }
}
