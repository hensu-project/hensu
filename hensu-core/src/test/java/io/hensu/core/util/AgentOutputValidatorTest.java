package io.hensu.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AgentOutputValidator")
class AgentOutputValidatorTest {

    // -- containsDangerousChars ------------------------------------------------

    @ParameterizedTest(name = "allowed char: {0}")
    @ValueSource(
            strings = {
                "column1\tcolumn2", // TAB (0x09)
                "line1\nline2", // LF  (0x0A)
                "line1\r\nline2", // CR  (0x0D)
                "hello world" // SPACE (0x20)
            })
    void shouldAllowSafeWhitespace(String input) {
        assertThat(AgentOutputValidator.containsDangerousChars(input)).isFalse();
    }

    @ParameterizedTest(name = "rejected control char: U+{0}")
    @MethodSource("dangerousControlChars")
    // label is consumed by JUnit 5 @ParameterizedTest(name=) for test display names, not by method
    // body
    void shouldRejectDangerousControlChars(@SuppressWarnings("unused") String label, String input) {
        assertThat(AgentOutputValidator.containsDangerousChars(input)).isTrue();
    }

    static Stream<Arguments> dangerousControlChars() {
        return Stream.of(
                Arguments.of("0000 NUL", "before\u0000after"),
                Arguments.of("0008 BS", "back\u0008space"),
                Arguments.of("000B VT", "line\u000Bbreak"),
                Arguments.of("000C FF", "page\u000Cbreak"),
                Arguments.of("000E SO", "bad\u000Echar"),
                Arguments.of("001F US", "bad\u001Fchar"),
                Arguments.of("007F DEL", "del\u007Fchar"));
    }

    @Test
    @DisplayName("control char buried deep in long string is detected")
    void shouldDetectControlCharEmbeddedInLongString() {
        String payload = "x".repeat(1000) + "\u0007" + "y".repeat(1000);
        assertThat(AgentOutputValidator.containsDangerousChars(payload)).isTrue();
    }

    // -- containsUnicodeTricks ------------------------------------------------

    @ParameterizedTest(name = "rejected Unicode trick: U+{0}")
    @MethodSource("dangerousUnicodeTricks")
    // label is consumed by JUnit 5 @ParameterizedTest(name=) for test display names, not by method
    // body
    void shouldRejectUnicodeTricks(@SuppressWarnings("unused") String label, String input) {
        assertThat(AgentOutputValidator.containsUnicodeTricks(input)).isTrue();
    }

    static Stream<Arguments> dangerousUnicodeTricks() {
        return Stream.of(
                Arguments.of("202A LRE", "text\u202Amore"),
                Arguments.of("202E RLO", "benign\u202Evil"),
                Arguments.of("2066 LRI", "text\u2066isolated"),
                Arguments.of("2069 PDI", "text\u2069end"),
                Arguments.of("200B ZWSP", "zero\u200Bwidth"),
                Arguments.of("200D ZWJ", "zero\u200Djoiner"),
                Arguments.of("FEFF BOM", "\uFEFFprefixed"));
    }

    @Test
    @DisplayName("legitimate Arabic text must not produce false positives")
    void shouldAllowLegitimateArabicText() {
        // "العربية" — natural RTL script, no directional override characters
        assertThat(
                        AgentOutputValidator.containsUnicodeTricks(
                                "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"))
                .isFalse();
    }

    @Test
    @DisplayName("range boundaries must not overshoot into safe chars")
    void shouldNotOvershootRangeBoundaries() {
        // U+202F (NARROW NO-BREAK SPACE) is immediately after U+202E
        assertThat(AgentOutputValidator.containsUnicodeTricks("ok\u202Fspacing")).isFalse();
        // U+206A is immediately after U+2069
        assertThat(AgentOutputValidator.containsUnicodeTricks("ok\u206Atext")).isFalse();
        // U+0080 is immediately after DEL
        assertThat(AgentOutputValidator.containsDangerousChars("\u0080extended")).isFalse();
    }

    // -- exceedsSizeLimit -----------------------------------------------------

    @Test
    @DisplayName("byte length used, not char length — catches surrogate pair / multi-byte bugs")
    void shouldCountBytesNotCharLength() {
        // U+1F600 GRINNING FACE: 4 bytes in UTF-8, but String.length() = 2 (surrogate pair)
        String emoji = "\uD83D\uDE00"; // 😀
        assertThat(emoji).hasSize(2);
        assertThat(AgentOutputValidator.exceedsSizeLimit(emoji, 3)).isTrue();
        assertThat(AgentOutputValidator.exceedsSizeLimit(emoji, 4)).isFalse();

        // € — 3 bytes in UTF-8, String.length() = 1
        String euro = "\u20AC";
        assertThat(AgentOutputValidator.exceedsSizeLimit(euro, 2)).isTrue();
        assertThat(AgentOutputValidator.exceedsSizeLimit(euro, 3)).isFalse();
    }

    // -- validate (integration) -----------------------------------------------

    @Test
    @DisplayName("validate returns violation reason for dangerous output")
    void shouldReturnViolationForDangerousOutput() {
        assertThat(AgentOutputValidator.validate("clean output")).isEmpty();
        assertThat(AgentOutputValidator.validate("bad\u0000output")).isPresent();
        assertThat(AgentOutputValidator.validate("bidi\u202Eattack")).isPresent();
    }
}
