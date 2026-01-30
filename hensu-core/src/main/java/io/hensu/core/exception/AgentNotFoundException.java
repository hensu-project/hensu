package io.hensu.core.exception;

import java.io.Serial;

public class AgentNotFoundException extends Exception {
    @Serial private static final long serialVersionUID = -5533800018143448937L;

    public AgentNotFoundException(String message) {
        super(message);
    }
}
