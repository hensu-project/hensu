package io.hensu.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.hensu.server.mcp.ClusterSignals;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/// The listener's connection lifecycle against PostgreSQL.
class PgNotificationListenerTest extends JdbcRepositoryTestBase {

    @Test
    void stopHandsTheListeningConnectionBackUsable() throws Exception {
        AtomicReference<Boolean> validAtClose = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch listening = new CountDownLatch(1);
        ClusterSignals signals = new ClusterSignals();
        // The listener resyncs once its LISTEN statements have run.
        signals.onRunnable(listening::countDown);
        PgNotificationListener listener =
                new PgNotificationListener(recording(validAtClose, closed), signals, _ -> {});
        listener.start();
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();

        listener.stop();

        // Interrupting the thread inside its socket read would close the socket, and a pool
        // would get back a dead connection.
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(validAtClose.get()).isTrue();
    }

    /// The test data source, recording whether the connection still works when it is closed.
    private static DataSource recording(
            AtomicReference<Boolean> validAtClose, CountDownLatch closed) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (_, method, args) -> {
                            Object result = call(dataSource, method, args);
                            if (!method.getName().equals("getConnection")) {
                                return result;
                            }
                            Connection connection = (Connection) result;
                            return Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (_, m, a) -> {
                                        if (m.getName().equals("close")) {
                                            validAtClose.set(connection.isValid(1));
                                            closed.countDown();
                                        }
                                        return call(connection, m, a);
                                    });
                        });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
