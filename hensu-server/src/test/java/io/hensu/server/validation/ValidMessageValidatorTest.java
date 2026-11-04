package io.hensu.server.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/// An optional body (`allowBlank`) may be absent, but one that is present gets the same
/// boundary checks as a required body.
class ValidMessageValidatorTest {

    private final ConstraintValidatorContext ctx =
            mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    @SuppressWarnings("unused")
    private static void optionalBody(@ValidMessage(maxBytes = 16, allowBlank = true) String body) {}

    @Test
    void anOptionalBodyMayBeAbsentOrBlank() throws Exception {
        ValidMessageValidator validator = optionalBodyValidator();

        assertThat(validator.isValid(null, ctx)).isTrue();
        assertThat(validator.isValid("  ", ctx)).isTrue();
    }

    @Test
    void anOptionalBodyThatIsPresentIsStillBounded() throws Exception {
        ValidMessageValidator validator = optionalBodyValidator();

        assertThat(validator.isValid("{\"pad\":\"" + "x".repeat(16) + "\"}", ctx)).isFalse();
        assertThat(validator.isValid("{\"a\":\"\u0001\"}", ctx)).isFalse();
        assertThat(validator.isValid("{\"max\":4}", ctx)).isTrue();
    }

    private static ValidMessageValidator optionalBodyValidator() throws Exception {
        Method method =
                ValidMessageValidatorTest.class.getDeclaredMethod("optionalBody", String.class);
        ValidMessageValidator validator = new ValidMessageValidator();
        validator.initialize((ValidMessage) method.getParameterAnnotations()[0][0]);
        return validator;
    }
}
