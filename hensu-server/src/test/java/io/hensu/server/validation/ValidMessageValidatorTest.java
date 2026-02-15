package io.hensu.server.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ValidMessageValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // ———————————————— Test DTO ————————————————

    record MessageDto(@ValidMessage String body) {}

    record SmallMessageDto(@ValidMessage(maxBytes = 16) String body) {}

    // ———————————————— Null / Blank ————————————————

    @Test
    void shouldRejectNullBody() {
        var violations = validator.validate(new MessageDto(null));
        assertSingleViolation(violations, "Message body is required");
    }

    @Test
    void shouldRejectBlankBody() {
        var violations = validator.validate(new MessageDto("   "));
        assertSingleViolation(violations, "Message body is required");
    }

    // ———————————————— Size ————————————————

    @Test
    void shouldRejectOversizedBody() {
        String oversized = "x".repeat(17);
        var violations = validator.validate(new SmallMessageDto(oversized));
        assertSingleViolation(violations, "Message exceeds maximum allowed size");
    }

    @Test
    void shouldAcceptBodyAtExactLimit() {
        String exact = "x".repeat(16);
        var violations = validator.validate(new SmallMessageDto(exact));
        assertThat(violations).isEmpty();
    }

    // ———————————————— Control Characters ————————————————

    @Test
    void shouldRejectBodyWithNullByte() {
        var violations = validator.validate(new MessageDto("hello\0world"));
        assertSingleViolation(violations, "Message contains illegal control characters");
    }

    @Test
    void shouldRejectBodyWithBellChar() {
        var violations = validator.validate(new MessageDto("alert\u0007"));
        assertSingleViolation(violations, "Message contains illegal control characters");
    }

    // ———————————————— Valid ————————————————

    @Test
    void shouldAcceptValidJsonBody() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{}}";
        var violations = validator.validate(new MessageDto(json));
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldAcceptBodyWithNewlinesAndTabs() {
        String text = "line1\nline2\ttab";
        var violations = validator.validate(new MessageDto(text));
        assertThat(violations).isEmpty();
    }

    // ———————————————— Helpers ————————————————

    private static void assertSingleViolation(
            Set<? extends ConstraintViolation<?>> violations, String expectedMessage) {
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo(expectedMessage);
    }
}
