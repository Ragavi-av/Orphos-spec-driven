package io.orphos.eip.voice.service;

import io.orphos.eip.voice.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class VoiceFeatureGateTest {

    private FakeHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void blankRegistryUrlFailsClosed() {
        assertFalse(new VoiceFeatureGate("").isEnabled("Bearer t", null));
        assertFalse(new VoiceFeatureGate(null).isEnabled("Bearer t", null));
    }

    @Test
    void missingOrBlankBearerFailsClosedWithoutCallingRegistry() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true}");
        VoiceFeatureGate gate = new VoiceFeatureGate(server.url());
        assertFalse(gate.isEnabled(null, "t1"));
        assertFalse(gate.isEnabled("  ", "t1"));
        assertEquals(0, server.count());
    }

    @Test
    void enabledWhenRegistryReportsEnabled() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true,\"tenant\":\"zenitus\"}");
        assertTrue(new VoiceFeatureGate(server.url()).isEnabled("Bearer abc", null));
    }

    @Test
    void disabledWhenRegistryReportsDisabled() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":false}");
        assertFalse(new VoiceFeatureGate(server.url()).isEnabled("Bearer abc", null));
    }

    @Test
    void disabledWhenEnabledFieldMissing() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{}");
        assertFalse(new VoiceFeatureGate(server.url()).isEnabled("Bearer abc", null));
    }

    @Test
    void failsClosedOnRegistryErrorStatuses() throws Exception {
        for (int status : new int[]{401, 403, 500, 503}) {
            try (FakeHttpServer s = new FakeHttpServer().replyWith(status, "{\"enabled\":true}")) {
                assertFalse(new VoiceFeatureGate(s.url()).isEnabled("Bearer abc", null), "status " + status);
            }
        }
    }

    @Test
    void failsClosedOnMalformedBody() throws Exception {
        server = new FakeHttpServer().replyWith(200, "<html>not json</html>");
        assertFalse(new VoiceFeatureGate(server.url()).isEnabled("Bearer abc", null));
    }

    @Test
    void failsClosedWhenRegistryUnreachable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertFalse(new VoiceFeatureGate("http://127.0.0.1:" + port).isEnabled("Bearer abc", null));
    }

    @Test
    void forwardsCallerBearerAndTenantHeaderToFeatureEndpoint() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true}");
        new VoiceFeatureGate(server.url()).isEnabled("Bearer caller-token", "acme");

        var req = server.requests().get(0);
        assertEquals("GET", req.method());
        assertEquals("/api/platform/features/voice/enabled", req.path());
        assertEquals("Bearer caller-token", req.header("Authorization"));
        assertEquals("acme", req.header("X-Tenant-Id"));
    }

    @Test
    void omitsTenantHeaderWhenBlank() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true}");
        new VoiceFeatureGate(server.url()).isEnabled("Bearer t", "  ");
        assertNull(server.requests().get(0).header("X-Tenant-Id"));
    }

    @Test
    void trailingSlashOnRegistryUrlIsStripped() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true}");
        assertTrue(new VoiceFeatureGate(server.url() + "//").isEnabled("Bearer t", null));
        assertEquals("/api/platform/features/voice/enabled", server.requests().get(0).path());
    }

    // ── caching ──────────────────────────────────────────────────────────────

    @Test
    void repeatedLookupsForSameCallerAreServedFromCache() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":true}");
        VoiceFeatureGate gate = new VoiceFeatureGate(server.url());
        assertTrue(gate.isEnabled("Bearer t", "acme"));
        assertTrue(gate.isEnabled("Bearer t", "acme"));
        assertTrue(gate.isEnabled("Bearer t", "acme"));
        assertEquals(1, server.count());
    }

    @Test
    void negativeAnswersAreCachedToo() throws Exception {
        server = new FakeHttpServer().replyWith(500, "x");
        VoiceFeatureGate gate = new VoiceFeatureGate(server.url());
        assertFalse(gate.isEnabled("Bearer t", null));
        assertFalse(gate.isEnabled("Bearer t", null));
        assertEquals(1, server.count());
    }

    @Test
    void differentTenantsAndTokensAreCachedSeparately() throws Exception {
        server = new FakeHttpServer().handle(r -> new FakeHttpServer.Reply(200,
                "{\"enabled\":" + "acme".equals(r.header("X-Tenant-Id")) + "}"));
        VoiceFeatureGate gate = new VoiceFeatureGate(server.url());
        assertTrue(gate.isEnabled("Bearer t", "acme"));
        assertFalse(gate.isEnabled("Bearer t", "other"));
        assertFalse(gate.isEnabled("Bearer t2", "other"));
        assertEquals(3, server.count());
    }

    @Test
    void invalidateForcesFreshLookup() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{\"enabled\":false}");
        VoiceFeatureGate gate = new VoiceFeatureGate(server.url());
        assertFalse(gate.isEnabled("Bearer t", null));

        server.replyWith(200, "{\"enabled\":true}");
        assertFalse(gate.isEnabled("Bearer t", null), "still cached");

        gate.invalidate();
        assertTrue(gate.isEnabled("Bearer t", null));
        assertEquals(2, server.count());
    }
}
