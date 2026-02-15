package io.hensu.server.persistence;

import java.io.Serial;

/// Unchecked exception for database persistence failures.
///
/// Wraps {@link java.sql.SQLException} to avoid checked exception propagation
/// through the {@link io.hensu.core.workflow.WorkflowRepository} and
/// {@link io.hensu.core.state.WorkflowStateRepository} interfaces, which
/// declare no checked exceptions.
///
/// @see JdbcWorkflowRepository
/// @see JdbcWorkflowStateRepository
public class PersistenceException extends RuntimeException {

    @Serial private static final long serialVersionUID = -7997332554400380982L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
