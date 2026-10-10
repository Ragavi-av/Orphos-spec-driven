package io.orphos.eip.proxy.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the cache-only-a-confirmed-answer rule described in {@link TenantInfoCache#find}: a
 * genuine 404 is cached (a second lookup never hits the network again), but an unexpected failure
 * (e.g. a 500) is NOT cached, so a second lookup retries against the network instead of reusing a
 * stale denial for the rest of the TTL.
 */
class TenantInfoCacheTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private TenantInfoCache cacheAgainst(String baseUrl) {
        TenantInfoCache cache = new TenantInfoCache(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMinutes(5));
        ReflectionTestUtils.setField(cache, "adminBaseUrl", baseUrl);
        return cache;
    }

    @Test
    void a404IsCached_secondLookupNeverHitsTheNetworkAgain() throws Exception {
        UUID tenantId = UUID.randomUUID();
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/tenants/" + tenantId, exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        TenantInfoCache cache = cacheAgainst("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(cache.find(tenantId)).isEmpty();
        assertThat(cache.find(tenantId)).isEmpty();
        assertThat(hits.get()).isEqualTo(1);   // second call served from cache, not the network
    }

    @Test
    void anUnexpectedFailureIsNotCached_secondLookupRetriesAndCanSucceed() throws Exception {
        UUID tenantId = UUID.randomUUID();
        AtomicInteger hits = new AtomicInteger();
        String body = """
                {"id":"%s","isolationMode":"SHARED","arcadeDatabase":"orphos","status":"ACTIVE"}
                """.formatted(tenantId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/tenants/" + tenantId, exchange -> {
            if (hits.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);   // first call: transient outage
                exchange.close();
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        server.start();
        TenantInfoCache cache = cacheAgainst("http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(cache.find(tenantId)).isEmpty();          // 503 — fails closed, not cached
        assertThat(cache.find(tenantId)).isPresent();         // retried the network, this time it succeeded
        assertThat(hits.get()).isEqualTo(2);
    }
}
