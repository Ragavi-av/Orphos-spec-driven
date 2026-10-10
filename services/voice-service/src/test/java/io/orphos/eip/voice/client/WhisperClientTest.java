package io.orphos.eip.voice.client;

import io.orphos.eip.voice.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WhisperClientTest {

    private FakeHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private WhisperClient client(String url) {
        return new WhisperClient(url, 30);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ── configuration ────────────────────────────────────────────────────────

    @Test
    void blankUrlIsNotConfigured() {
        assertFalse(client("").isConfigured());
        assertFalse(client(null).isConfigured());
    }

    @Test
    void blankUrlIsNotReachableAndTranscribeFailsCleanly() {
        WhisperClient c = client("");
        assertFalse(c.isReachable());
        var ex = assertThrows(WhisperClient.TranscriptionException.class,
                () -> c.transcribe(new byte[]{1}, "a.webm", null));
        assertEquals("Voice transcription is not configured.", ex.getMessage());
    }

    // ── isReachable ──────────────────────────────────────────────────────────

    @Test
    void reachableOn2xx() throws Exception {
        server = new FakeHttpServer().replyWith(200, "ok");
        assertTrue(client(server.url()).isReachable());
        assertEquals("/docs", server.requests().get(0).path());
    }

    @Test
    void reachableOn404BecauseSomethingIsListening() throws Exception {
        server = new FakeHttpServer().replyWith(404, "nope");
        assertTrue(client(server.url()).isReachable());
    }

    @Test
    void notReachableOn5xx() throws Exception {
        server = new FakeHttpServer().replyWith(503, "down");
        assertFalse(client(server.url()).isReachable());
    }

    @Test
    void notReachableWhenNothingListens() throws Exception {
        assertFalse(client("http://127.0.0.1:" + freePort()).isReachable());
    }

    @Test
    void trailingSlashesOnUrlAreStripped() throws Exception {
        server = new FakeHttpServer().replyWith(200, "ok");
        assertTrue(client(server.url() + "///").isReachable());
        assertEquals("/docs", server.requests().get(0).path());
    }

    // ── transcribe ───────────────────────────────────────────────────────────

    @Test
    void transcribeReturnsTrimmedText() throws Exception {
        server = new FakeHttpServer().replyWith(200, "  hello orphos \n");
        assertEquals("hello orphos", client(server.url()).transcribe(new byte[]{1, 2, 3}, "clip.webm", null));
    }

    @Test
    void transcribeSendsMultipartAudioFileWithFilenameAndBytes() throws Exception {
        server = new FakeHttpServer().replyWith(200, "text");
        byte[] audio = "RIFF-fake-audio".getBytes(StandardCharsets.UTF_8);
        client(server.url()).transcribe(audio, "voice.wav", null);

        var req = server.requests().get(0);
        assertEquals("POST", req.method());
        assertEquals("/asr", req.path());
        assertTrue(req.header("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = req.bodyText();
        assertTrue(body.contains("name=\"audio_file\"; filename=\"voice.wav\""));
        assertTrue(body.contains("RIFF-fake-audio"));
    }

    @Test
    void transcribeRequestsPlainTextTranscription() throws Exception {
        server = new FakeHttpServer().replyWith(200, "x");
        client(server.url()).transcribe(new byte[]{1}, "a.webm", null);
        String q = server.requests().get(0).query();
        assertTrue(q.contains("task=transcribe"));
        assertTrue(q.contains("output=txt"));
        assertTrue(q.contains("encode=true"));
        assertFalse(q.contains("language="));
    }

    @Test
    void languageHintIsForwardedAndTrimmed() throws Exception {
        server = new FakeHttpServer().replyWith(200, "x");
        client(server.url()).transcribe(new byte[]{1}, "a.webm", " fr ");
        assertTrue(server.requests().get(0).query().contains("language=fr"));
    }

    @Test
    void blankLanguageIsNotForwarded() throws Exception {
        server = new FakeHttpServer().replyWith(200, "x");
        client(server.url()).transcribe(new byte[]{1}, "a.webm", "   ");
        assertFalse(server.requests().get(0).query().contains("language"));
    }

    @Test
    void emptyTranscriptIsNotAnError() throws Exception {
        server = new FakeHttpServer().replyWith(200, "   \n");
        assertEquals("", client(server.url()).transcribe(new byte[]{1}, "a.webm", null));
    }

    @Test
    void emptyBodyIsNotAnError() throws Exception {
        server = new FakeHttpServer().replyWith(204, "");
        assertEquals("", client(server.url()).transcribe(new byte[]{1}, "a.webm", null));
    }

    @Test
    void non2xxBecomesUserFacingExceptionWithoutLeakingUpstreamDetail() throws Exception {
        server = new FakeHttpServer().replyWith(422, "{\"detail\":\"no audio_file\"}");
        var ex = assertThrows(WhisperClient.TranscriptionException.class,
                () -> client(server.url()).transcribe(new byte[]{1}, "a.webm", null));
        assertEquals("The speech recogniser rejected that clip.", ex.getMessage());
        assertFalse(ex.getMessage().contains("audio_file"));
    }

    @Test
    void serverErrorBecomesUserFacingException() throws Exception {
        server = new FakeHttpServer().replyWith(500, "boom");
        assertThrows(WhisperClient.TranscriptionException.class,
                () -> client(server.url()).transcribe(new byte[]{1}, "a.webm", null));
    }

    @Test
    void connectionFailureBecomesUnavailableException() throws Exception {
        String url = "http://127.0.0.1:" + freePort();
        var ex = assertThrows(WhisperClient.TranscriptionException.class,
                () -> client(url).transcribe(new byte[]{1}, "a.webm", null));
        assertEquals("Speech recognition is unavailable right now.", ex.getMessage());
    }
}
