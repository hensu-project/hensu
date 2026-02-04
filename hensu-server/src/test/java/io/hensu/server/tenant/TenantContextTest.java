package io.hensu.server.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hensu.server.tenant.TenantContext.TenantInfo;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @Nested
    class ContextBinding {

        @Test
        void shouldBindAndRetrieveTenantContext() throws Exception {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            String result =
                    TenantContext.runAs(
                            tenant,
                            () -> {
                                TenantInfo current = TenantContext.current();
                                return current.tenantId();
                            });

            assertThat(result).isEqualTo("tenant-1");
        }

        @Test
        void shouldThrowWhenNoContextBound() {
            assertThatThrownBy(TenantContext::current)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant context bound");
        }

        @Test
        void shouldReturnNullForCurrentOrNullWhenNotBound() {
            assertThat(TenantContext.currentOrNull()).isNull();
        }

        @Test
        void shouldReturnFalseForIsBoundWhenNotBound() {
            assertThat(TenantContext.isBound()).isFalse();
        }

        @Test
        void shouldReturnTrueForIsBoundWhenBound() {
            TenantInfo tenant = TenantInfo.simple("tenant-1");

            TenantContext.runAs(
                    tenant,
                    () -> {
                        assertThat(TenantContext.isBound()).isTrue();
                    });
        }

        @Test
        void shouldSupportNestedContexts() throws Exception {
            TenantInfo outer = TenantInfo.simple("outer");
            TenantInfo inner = TenantInfo.simple("inner");

            String result =
                    TenantContext.runAs(
                            outer,
                            () -> {
                                assertThat(TenantContext.current().tenantId()).isEqualTo("outer");

                                return TenantContext.runAs(
                                        inner,
                                        () -> {
                                            assertThat(TenantContext.current().tenantId())
                                                    .isEqualTo("inner");
                                            return "nested";
                                        });
                            });

            assertThat(result).isEqualTo("nested");
        }

        @Test
        void shouldRunRunnableWithContext() {
            TenantInfo tenant = TenantInfo.simple("tenant-1");
            AtomicReference<String> captured = new AtomicReference<>();

            TenantContext.runAs(tenant, () -> captured.set(TenantContext.current().tenantId()));

            assertThat(captured.get()).isEqualTo("tenant-1");
        }
    }

    @Nested
    class ThreadIsolation {

        @Test
        void shouldIsolateContextBetweenThreads() throws Exception {
            TenantInfo tenant1 = TenantInfo.simple("tenant-1");
            TenantInfo tenant2 = TenantInfo.simple("tenant-2");

            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<String> thread1Result = new AtomicReference<>();
            AtomicReference<String> thread2Result = new AtomicReference<>();

            Thread t1 =
                    new Thread(
                            () ->
                                    TenantContext.runAs(
                                            tenant1,
                                            () -> {
                                                try {
                                                    Thread.sleep(50);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                thread1Result.set(
                                                        TenantContext.current().tenantId());
                                                latch.countDown();
                                            }));

            Thread t2 =
                    new Thread(
                            () ->
                                    TenantContext.runAs(
                                            tenant2,
                                            () -> {
                                                thread2Result.set(
                                                        TenantContext.current().tenantId());
                                                latch.countDown();
                                            }));

            t1.start();
            t2.start();
            latch.await();

            assertThat(thread1Result.get()).isEqualTo("tenant-1");
            assertThat(thread2Result.get()).isEqualTo("tenant-2");
        }
    }

    @Nested
    class TenantInfoTest {

        @Test
        void shouldCreateSimpleTenantInfo() {
            TenantInfo info = TenantInfo.simple("tenant-1");

            assertThat(info.tenantId()).isEqualTo("tenant-1");
            assertThat(info.mcpEndpoint()).isNull();
            assertThat(info.credentials()).isEmpty();
            assertThat(info.hasMcp()).isFalse();
        }

        @Test
        void shouldCreateTenantInfoWithMcp() {
            TenantInfo info = TenantInfo.withMcp("tenant-1", "http://mcp.local:8080");

            assertThat(info.tenantId()).isEqualTo("tenant-1");
            assertThat(info.mcpEndpoint()).isEqualTo("http://mcp.local:8080");
            assertThat(info.hasMcp()).isTrue();
        }

        @Test
        void shouldCreateTenantInfoWithCredentials() {
            Map<String, String> creds = Map.of("api_key", "secret123", "token", "xyz");
            TenantInfo info = new TenantInfo("tenant-1", "http://mcp.local", creds);

            assertThat(info.credential("api_key")).isEqualTo("secret123");
            assertThat(info.credential("token")).isEqualTo("xyz");
            assertThat(info.credential("unknown")).isNull();
        }

        @Test
        void shouldThrowWhenTenantIdIsNull() {
            assertThatThrownBy(() -> new TenantInfo(null, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tenantId");
        }

        @Test
        void shouldDefaultCredentialsToEmptyMap() {
            TenantInfo info = new TenantInfo("tenant-1", null, null);

            assertThat(info.credentials()).isNotNull().isEmpty();
        }

        @Test
        void shouldMakeCredentialsImmutable() {
            Map<String, String> creds = new java.util.HashMap<>();
            creds.put("key", "value");
            TenantInfo info = new TenantInfo("tenant-1", null, creds);

            assertThatThrownBy(() -> info.credentials().put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
