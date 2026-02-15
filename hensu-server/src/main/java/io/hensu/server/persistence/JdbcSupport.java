package io.hensu.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/// Lightweight JDBC helper that eliminates repeated try-with-resources and
/// {@link SQLException} boilerplate in the repository implementations.
///
/// By separating SQL (always a {@code static final} constant) from parameter
/// binding (always via {@link StatementPreparer}), this class makes accidental
/// SQL string concatenation structurally impossible.
///
/// ### Contracts
/// - **Precondition**: {@link DataSource} is a valid Agroal-managed pool
/// - **Postcondition**: every acquired connection is released via try-with-resources
///
/// @implNote Thread-safe. Stateless beyond the injected {@link DataSource}.
/// Each call acquires and releases its own connection from the Agroal pool.
///
/// @see JdbcWorkflowRepository
/// @see JdbcWorkflowStateRepository
final class JdbcSupport {

    private final DataSource dataSource;

    JdbcSupport(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /// Executes an INSERT, UPDATE, or DELETE statement.
    ///
    /// @param sql the SQL statement, not null
    /// @param preparer binds parameters to the statement, not null
    /// @param errorContext message prefix for {@link PersistenceException}, not null
    /// @return number of affected rows
    /// @throws PersistenceException if the statement fails
    int update(String sql, StatementPreparer preparer, String errorContext) {
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            preparer.prepare(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(errorContext, e);
        }
    }

    /// Executes a SELECT returning zero or one mapped row.
    ///
    /// @param <T> the domain type produced by the mapper
    /// @param sql the SELECT statement, not null
    /// @param preparer binds parameters to the statement, not null
    /// @param mapper converts a {@link ResultSet} row to a domain object, not null
    /// @param errorContext message prefix for {@link PersistenceException}, not null
    /// @return the mapped row if present, empty otherwise, never null
    /// @throws PersistenceException if the query fails
    <T> Optional<T> queryOne(
            String sql, StatementPreparer preparer, RowMapper<T> mapper, String errorContext) {
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            preparer.prepare(ps);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException(errorContext, e);
        }
    }

    /// Executes a SELECT returning zero or more mapped rows.
    ///
    /// @param <T> the domain type produced by the mapper
    /// @param sql the SELECT statement, not null
    /// @param preparer binds parameters to the statement, not null
    /// @param mapper converts each {@link ResultSet} row to a domain object, not null
    /// @param errorContext message prefix for {@link PersistenceException}, not null
    /// @return list of mapped rows, may be empty, never null
    /// @throws PersistenceException if the query fails
    <T> List<T> queryList(
            String sql, StatementPreparer preparer, RowMapper<T> mapper, String errorContext) {
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            preparer.prepare(ps);
            try (var rs = ps.executeQuery()) {
                var results = new ArrayList<T>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new PersistenceException(errorContext, e);
        }
    }

    /// Binds parameters to a {@link PreparedStatement} before execution.
    ///
    /// {@snippet :
    /// StatementPreparer binder = ps -> {
    ///     ps.setString(1, tenantId);
    ///     ps.setString(2, workflowId);
    /// };
    /// }
    ///
    /// @see JdbcSupport#update
    /// @see JdbcSupport#queryOne
    @FunctionalInterface
    interface StatementPreparer {

        /// Binds parameters to the prepared statement.
        ///
        /// @param ps the statement to bind parameters to, not null
        /// @throws SQLException if parameter binding fails
        void prepare(PreparedStatement ps) throws SQLException;
    }

    /// Maps a single {@link ResultSet} row to a domain object.
    ///
    /// {@snippet :
    /// RowMapper<String> nameMapper = rs -> rs.getString("name");
    /// }
    ///
    /// @param <T> the domain type to produce
    /// @see JdbcSupport#queryOne
    /// @see JdbcSupport#queryList
    @FunctionalInterface
    interface RowMapper<T> {

        /// Maps the current row to a domain object.
        ///
        /// @param rs positioned at the current row, not null
        /// @return the mapped domain object, not null
        /// @throws SQLException if column access fails
        T map(ResultSet rs) throws SQLException;
    }
}
