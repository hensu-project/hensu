package io.hensu.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/// Pins the grammar {@link MiniYaml} accepts and the line numbers it reports.
///
/// The parser exists because `hensu-core` may not depend on a YAML library, so
/// its behaviour is a specification rather than an implementation detail: every
/// configuration file in the product is read through it, and a silent misparse
/// would turn a typo into a different, plausible configuration.
class MiniYamlTest {

    @Test
    void shouldParseNestedMapsListsAndScalarForms() {
        MiniYaml.Mapping root =
                MiniYaml.parse(
                        """
                        commands:
                          run-tests:
                            exec: ["./gradlew", "test"]
                            timeout: 120000
                            enabled: true
                            note: 'it''s fine'
                            sandbox:
                              write:
                                - "build/"
                                - ".gradle/"
                        """);

        MiniYaml.Mapping command =
                root.require("commands").asMapping().require("run-tests").asMapping();
        assertThat(command.require("exec").asStringList()).containsExactly("./gradlew", "test");
        assertThat(command.require("timeout").asLong()).isEqualTo(120000L);
        assertThat(command.require("enabled").asBoolean()).isTrue();
        assertThat(command.require("note").asString()).isEqualTo("it's fine");
        assertThat(command.require("sandbox").asMapping().require("write").asStringList())
                .containsExactly("build/", ".gradle/");
    }

    @Test
    void shouldParseFlowMappingCarryingNestedFlowList() {
        MiniYaml.Mapping root =
                MiniYaml.parse(
                        "mode: { type: string, enum: [\"fast\", \"slow\"], required: true }");

        MiniYaml.Mapping mode = root.require("mode").asMapping();
        assertThat(mode.require("type").asString()).isEqualTo("string");
        assertThat(mode.require("enum").asStringList()).containsExactly("fast", "slow");
        assertThat(mode.require("required").asBoolean()).isTrue();
    }

    @Test
    void shouldTreatHashInsideQuotesAsContentAndOutsideAsComment() {
        MiniYaml.Mapping root =
                MiniYaml.parse(
                        """
                        # leading comment
                        pattern: "^[A-Z]#[0-9]+$"   # trailing comment
                        plain: value
                        """);

        assertThat(root.require("pattern").asString()).isEqualTo("^[A-Z]#[0-9]+$");
        assertThat(root.require("plain").asString()).isEqualTo("value");
        assertThat(root.keys()).containsExactly("pattern", "plain");
    }

    @Test
    void shouldReportTheLineEachNodeBeganOn() {
        MiniYaml.Mapping root =
                MiniYaml.parse(
                        """
                        commands:
                          build:
                            timeout: 5000
                        """);

        MiniYaml.Mapping build = root.require("commands").asMapping().require("build").asMapping();
        assertThat(build.line()).isEqualTo(3);
        assertThat(build.require("timeout").line()).isEqualTo(3);
    }

    @Test
    void shouldRejectTabIndentationWithItsLine() {
        assertThatThrownBy(() -> MiniYaml.parse("commands:\n\tbuild: x\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("tabs are not permitted")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectIndentationThatIsNotAMultipleOfTwo() {
        assertThatThrownBy(() -> MiniYaml.parse("commands:\n   build: x\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("multiple of 2 spaces")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectOverIndentationThatWouldSilentlyReparent() {
        assertThatThrownBy(() -> MiniYaml.parse("commands:\n  build:\n      timeout: 5\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("expected an indentation of 4 spaces, found 6")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(3);
    }

    @Test
    void shouldRejectDuplicateKeysRatherThanLetTheLastOneWin() {
        assertThatThrownBy(() -> MiniYaml.parse("timeout: 1\ntimeout: 2\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("duplicate key 'timeout'")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectMappingsNestedBeyondTheSupportedDepth() {
        String tooDeep =
                """
                a:
                  b:
                    c:
                      d:
                        e:
                          f: value
                """;

        assertThatThrownBy(() -> MiniYaml.parse(tooDeep))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("supported depth of " + MiniYaml.MAX_DEPTH)
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(6);
    }

    @Test
    void shouldRejectUnterminatedFlowCollections() {
        assertThatThrownBy(() -> MiniYaml.parse("exec: [\"a\", \"b\"\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("unterminated flow list");
    }

    @Test
    void shouldRejectContentThatHasNoParentKey() {
        assertThatThrownBy(() -> MiniYaml.parse("a: 1\n  b: 2\n"))
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("has no parent key")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(2);
    }

    @Test
    void shouldReportTheLineWhenAValueHasTheWrongShape() {
        MiniYaml.Mapping root = MiniYaml.parse("commands:\n  timeout: not-a-number\n");

        assertThatThrownBy(() -> root.require("commands").asMapping().require("timeout").asLong())
                .isInstanceOf(MiniYamlException.class)
                .hasMessageContaining("expected a number, found 'not-a-number'")
                .extracting(e -> ((MiniYamlException) e).line())
                .isEqualTo(2);
    }
}
