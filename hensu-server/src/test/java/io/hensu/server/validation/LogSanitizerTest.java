package io.hensu.server.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void shouldReturnNullStringForNullInput() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
    }

    @Test
    void shouldReturnCleanStringUnchanged() {
        assertThat(LogSanitizer.sanitize("order-processing")).isEqualTo("order-processing");
    }

    @Test
    void shouldStripNewline() {
        assertThat(LogSanitizer.sanitize("fake\nINFO forged log entry"))
                .isEqualTo("fakeINFO forged log entry");
    }

    @Test
    void shouldStripCarriageReturn() {
        assertThat(LogSanitizer.sanitize("fake\rINFO forged")).isEqualTo("fakeINFO forged");
    }

    @Test
    void shouldStripCrLfSequence() {
        assertThat(LogSanitizer.sanitize("line1\r\nline2")).isEqualTo("line1line2");
    }

    @Test
    void shouldPreserveSpecialCharsThatAreNotControlChars() {
        assertThat(LogSanitizer.sanitize("id=abc&version=1.0<script>"))
                .isEqualTo("id=abc&version=1.0<script>");
    }

    @Test
    void shouldHandleEmptyString() {
        assertThat(LogSanitizer.sanitize("")).isEqualTo("");
    }
}
