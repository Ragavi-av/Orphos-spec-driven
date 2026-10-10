package io.orphos.eip.voice.component;

import io.orphos.eip.voice.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class VoiceServiceRegistrationTest {

    private FakeHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private VoiceServiceRegistration reg(String url, String token) {
        return new VoiceServiceRegistration(url, "http://eip-voice:8099/actuator/health", token, "2.3.4");
    }

    @Test
    void registersWithExpectedPayloadAndInternalToken() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{}");
        reg(server.url(), "secret-token").registerOnStartup();

        assertEquals(1, server.count());
        var req = server.requests().get(0);
        assertEquals("POST", req.method());
        assertEquals("/api/platform/services/register", req.path());
        assertEquals("secret-token", req.header("X-Internal-Token"));
        assertTrue(req.header("Content-Type").startsWith("application/json"));
        String body = req.bodyText();
        assertTrue(body.contains("\"serviceId\":\"voice-transcription\""));
        assertTrue(body.contains("\"tier\":\"BASE\""));
        assertTrue(body.contains("\"version\":\"2.3.4\""));
        assertTrue(body.contains("\"healthUrl\":\"http://eip-voice:8099/actuator/health\""));
        assertTrue(body.contains("voice.transcribe"));
        assertTrue(body.contains("voice.status"));
    }

    @Test
    void omitsTokenHeaderWhenNoneConfigured() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{}");
        reg(server.url(), "").registerOnStartup();
        assertNull(server.requests().get(0).header("X-Internal-Token"));
    }

    @Test
    void stopsRetryingOnceRegistered() throws Exception {
        server = new FakeHttpServer().replyWith(200, "{}");
        VoiceServiceRegistration r = reg(server.url(), "t");
        r.registerOnStartup();
        r.retryUntilRegistered();
        r.retryUntilRegistered();
        assertEquals(1, server.count());
    }

    @Test
    void retriesAfterRejectionUntilItSticks() throws Exception {
        server = new FakeHttpServer().replyWith(503, "not ready");
        VoiceServiceRegistration r = reg(server.url(), "t");
        r.registerOnStartup();
        r.retryUntilRegistered();
        assertEquals(2, server.count());

        server.replyWith(200, "{}");
        r.retryUntilRegistered();
        assertEquals(3, server.count());

        r.retryUntilRegistered();
        assertEquals(3, server.count(), "registered, no further attempts");
    }

    @Test
    void unreachableRegistryDoesNotThrowAndIsRetried() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        VoiceServiceRegistration r = reg("http://127.0.0.1:" + port, "t");
        assertDoesNotThrow(r::registerOnStartup);
        assertDoesNotThrow(r::retryUntilRegistered);
    }

    @Test
    void blankRegistryUrlSkipsRegistration() {
        assertDoesNotThrow(() -> reg("", "t").registerOnStartup());
        assertDoesNotThrow(() -> reg(null, "t").registerOnStartup());
    }
}
