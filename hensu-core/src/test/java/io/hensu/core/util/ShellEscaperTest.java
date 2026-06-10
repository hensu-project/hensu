package io.hensu.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ShellEscaperTest {

    @Test
    void shouldWrapSimpleValueInSingleQuotes() {
        assertThat(ShellEscaper.escape("hello")).isEqualTo("'hello'");
    }

    @Test
    void shouldEscapeEmbeddedSingleQuotes() {
        assertThat(ShellEscaper.escape("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void shouldReturnEmptyQuotesForNull() {
        assertThat(ShellEscaper.escape(null)).isEqualTo("''");
    }

    @Test
    void shouldReturnEmptyQuotesForEmptyString() {
        assertThat(ShellEscaper.escape("")).isEqualTo("''");
    }

    @ParameterizedTest
    @MethodSource("shellMetacharacters")
    void shouldNeutralizeShellMetacharacters(String input, String expected) {
        assertThat(ShellEscaper.escape(input)).isEqualTo(expected);
    }

    static Stream<Arguments> shellMetacharacters() {
        return Stream.of(
                Arguments.of("$(whoami)", "'$(whoami)'"),
                Arguments.of("`id`", "'`id`'"),
                Arguments.of("${HOME}", "'${HOME}'"),
                Arguments.of("; rm -rf /", "'; rm -rf /'"),
                Arguments.of("a && b", "'a && b'"),
                Arguments.of("a || b", "'a || b'"),
                Arguments.of("foo > /etc/passwd", "'foo > /etc/passwd'"));
    }
}
