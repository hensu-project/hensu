package io.hensu.core.execution.executor;

import java.io.Serial;

public class NodeExecutorNotFound extends Exception {
    @Serial private static final long serialVersionUID = 4569044560797025331L;

    public NodeExecutorNotFound(String message) {
        super(message);
    }
}
