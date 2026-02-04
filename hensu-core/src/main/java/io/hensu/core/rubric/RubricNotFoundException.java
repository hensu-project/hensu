package io.hensu.core.rubric;

import java.io.Serial;

public class RubricNotFoundException extends Exception {
    @Serial private static final long serialVersionUID = -5643851930675430477L;

    public RubricNotFoundException(String message) {
        super(message);
    }
}
