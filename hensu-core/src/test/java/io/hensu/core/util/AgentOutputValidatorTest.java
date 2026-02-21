package io.hensu.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentOutputValidator")
class AgentOutputValidatorTest {

    // ——————————————————————————————————————————————————————
    // containsDangerousChars
    // ——————————————————————————————————————————————————————

    @Test
    @DisplayName("TAB (0x09) is allowed — range \\x00-\\x08 must not accidentally include it")
    void shouldAllowTab() {
        assertThat(AgentOutputValidator.containsDangerousChars("column1\tcolumn2")).isFalse();
    }

    @Test
    @DisplayName("LF (0x0A) is allowed — multiline LLM output is normal")
    void shouldAllowLineFeed() {
        assertThat(AgentOutputValidator.containsDangerousChars("line1\nline2\nline3")).isFalse();
    }

    @Test
    @DisplayName("CR (0x0D) is allowed — Windows CRLF line endings permitted")
    void shouldAllowCarriageReturn() {
        assertThat(AgentOutputValidator.containsDangerousChars("line1\r\nline2")).isFalse();
    }

    @Test
    @DisplayName("BS (0x08) is rejected — end boundary of first range \\x00-\\x08")
    void shouldRejectBackspace() {
        assertThat(AgentOutputValidator.containsDangerousChars("back\u0008space")).isTrue();
    }

    @Test
    @DisplayName("VT (0x0B) is rejected — explicitly included between LF and FF")
    void shouldRejectVerticalTab() {
        assertThat(AgentOutputValidator.containsDangerousChars("line\u000Bbreak")).isTrue();
    }

    @Test
    @DisplayName("FF (0x0C) is rejected — explicitly included between VT and CR")
    void shouldRejectFormFeed() {
        assertThat(AgentOutputValidator.containsDangerousChars("page\u000Cbreak")).isTrue();
    }

    @Test
    @DisplayName("SO (0x0E) is rejected — start boundary of range \\x0E-\\x1F")
    void shouldRejectShiftOut() {
        assertThat(AgentOutputValidator.containsDangerousChars("bad\u000Echar")).isTrue();
    }

    @Test
    @DisplayName("US (0x1F) is rejected — end boundary of range \\x0E-\\x1F")
    void shouldRejectUnitSeparator() {
        assertThat(AgentOutputValidator.containsDangerousChars("bad\u001Fchar")).isTrue();
    }

    @Test
    @DisplayName("SPACE (0x20) is allowed — first printable ASCII char, pattern must not bleed up")
    void shouldAllowSpace() {
        assertThat(AgentOutputValidator.containsDangerousChars("hello world")).isFalse();
    }

    @Test
    @DisplayName("DEL (0x7F) is rejected — singleton at upper ASCII boundary")
    void shouldRejectDel() {
        assertThat(AgentOutputValidator.containsDangerousChars("del\u007Fchar")).isTrue();
    }

    @Test
    @DisplayName("0x80 is allowed — pattern must not bleed into extended ASCII / Latin-1")
    void shouldAllowFirstExtendedAscii() {
        assertThat(AgentOutputValidator.containsDangerousChars("\u0080extended")).isFalse();
    }

    @Test
    @DisplayName(
            "NUL byte (0x00) is rejected — classic injection and null-termination attack vector")
    void shouldRejectNulByte() {
        assertThat(AgentOutputValidator.containsDangerousChars("before\u0000after")).isTrue();
    }

    @Test
    @DisplayName("control char buried deep in long string is detected regardless of position")
    void shouldDetectControlCharEmbeddedInLongString() {
        String payload = "x".repeat(1000) + "\u0007" + "y".repeat(1000);
        assertThat(AgentOutputValidator.containsDangerousChars(payload)).isTrue();
    }

    @Test
    @DisplayName("null input returns false without throwing")
    void shouldReturnFalseForNullDangerous() {
        assertThat(AgentOutputValidator.containsDangerousChars(null)).isFalse();
    }

    // ——————————————————————————————————————————————————————
    // containsUnicodeTricks
    // ——————————————————————————————————————————————————————

    @Test
    @DisplayName("LRE (U+202A) is rejected — start of directional override range")
    void shouldRejectLre() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("text\u202Amore")).isTrue();
    }

    @Test
    @DisplayName(
            "RLO (U+202E) is rejected — end of directional override range, classic hiding attack")
    void shouldRejectRlo() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("benign\u202Evil")).isTrue();
    }

    @Test
    @DisplayName(
            "U+202F (NARROW NO-BREAK SPACE) is allowed — char immediately after U+202E, range must not overshoot")
    void shouldAllowNarrowNoBreakSpace() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("ok\u202Fspacing")).isFalse();
    }

    @Test
    @DisplayName("LRI (U+2066) is rejected — start of Unicode isolate range")
    void shouldRejectLri() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("text\u2066isolated")).isTrue();
    }

    @Test
    @DisplayName("PDI (U+2069) is rejected — end of Unicode isolate range")
    void shouldRejectPdi() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("text\u2069end")).isTrue();
    }

    @Test
    @DisplayName("U+206A is allowed — char immediately after U+2069, range must not overshoot")
    void shouldAllowCharJustAfterIsolateRange() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("ok\u206Atext")).isFalse();
    }

    @Test
    @DisplayName("ZWSP (U+200B) is rejected — start of zero-width range, invisible spacing attack")
    void shouldRejectZwsp() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("zero\u200Bwidth")).isTrue();
    }

    @Test
    @DisplayName("ZWJ (U+200D) is rejected — end of zero-width range")
    void shouldRejectZwj() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("zero\u200Djoiner")).isTrue();
    }

    @Test
    @DisplayName("BOM (U+FEFF) is rejected — byte-order mark has no place in LLM text content")
    void shouldRejectBom() {
        assertThat(AgentOutputValidator.containsUnicodeTricks("\uFEFFprefixed content")).isTrue();
    }

    @Test
    @DisplayName("legitimate Arabic text is allowed — RTL script must not produce false positives")
    void shouldAllowLegitimateArabicText() {
        // "العربية" — natural RTL script, no directional override characters
        assertThat(
                        AgentOutputValidator.containsUnicodeTricks(
                                "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"))
                .isFalse();
    }

    @Test
    @DisplayName("legitimate Hebrew text is allowed — another natural RTL script")
    void shouldAllowLegitimateHebrewText() {
        // "שלום" — natural RTL script
        assertThat(AgentOutputValidator.containsUnicodeTricks("\u05E9\u05DC\u05D5\u05DD"))
                .isFalse();
    }

    @Test
    @DisplayName("null input returns false without throwing")
    void shouldReturnFalseForNullUnicode() {
        assertThat(AgentOutputValidator.containsUnicodeTricks(null)).isFalse();
    }

    // ——————————————————————————————————————————————————————
    // exceedsSizeLimit
    // ——————————————————————————————————————————————————————

    @Test
    @DisplayName(
            "emoji (4 UTF-8 bytes) counted by byte length, not char length — catches length() vs getBytes() bug")
    void shouldCountBytesNotCharLengthForEmoji() {
        // U+1F600 GRINNING FACE: 4 bytes in UTF-8, but String.length() = 2 (surrogate pair)
        String emoji = "\uD83D\uDE00"; // 😀
        assertThat(emoji).hasSize(2); // confirms the trap: char length is 2
        assertThat(AgentOutputValidator.exceedsSizeLimit(emoji, 3)).isTrue();
        assertThat(AgentOutputValidator.exceedsSizeLimit(emoji, 4)).isFalse();
    }

    @Test
    @DisplayName("3-byte UTF-8 char (€) counted correctly at boundary")
    void shouldCountThreeByteCharsCorrectly() {
        String euro = "\u20AC"; // € — 3 bytes in UTF-8
        assertThat(AgentOutputValidator.exceedsSizeLimit(euro, 2)).isTrue();
        assertThat(AgentOutputValidator.exceedsSizeLimit(euro, 3)).isFalse();
    }

    @Test
    @DisplayName("payload at exact byte limit is not rejected")
    void shouldAllowPayloadAtExactLimit() {
        String s = "abc"; // 3 ASCII bytes
        assertThat(AgentOutputValidator.exceedsSizeLimit(s, 3)).isFalse();
    }

    @Test
    @DisplayName("payload one byte over limit is rejected")
    void shouldRejectPayloadOneByteOverLimit() {
        String s = "abcd"; // 4 ASCII bytes
        assertThat(AgentOutputValidator.exceedsSizeLimit(s, 3)).isTrue();
    }

    @Test
    @DisplayName("null input returns false without throwing")
    void shouldReturnFalseForNullSize() {
        assertThat(AgentOutputValidator.exceedsSizeLimit(null, 100)).isFalse();
    }
}
