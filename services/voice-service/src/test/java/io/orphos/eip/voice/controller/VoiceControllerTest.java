package io.orphos.eip.voice.controller;

import io.orphos.eip.voice.client.WhisperClient;
import io.orphos.eip.voice.service.VoiceFeatureGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller behaviour with hand-written stubs for the gate and Whisper (no Mockito, per repo convention). */
class VoiceControllerTest {

    static final class StubGate extends VoiceFeatureGate {
        volatile boolean enabled = true;
        final AtomicReference<String> bearer = new AtomicReference<>();
        final AtomicReference<String> tenant = new AtomicReference<>();

        StubGate() { super(""); }

        @Override public boolean isEnabled(String b, String t) {
            bearer.set(b);
            tenant.set(t);
            return enabled;
        }
    }

    static final class StubWhisper extends WhisperClient {
        volatile boolean reachable = true;
        volatile String text = "hello world";
        volatile RuntimeException failure;
        final AtomicReference<byte[]> audio = new AtomicReference<>();
        final AtomicReference<String> filename = new AtomicReference<>();
        final AtomicReference<String> language = new AtomicReference<>();
        volatile int calls;

        StubWhisper() { super("http://unused", 30); }

        @Override public boolean isReachable() { return reachable; }

        @Override public String transcribe(byte[] a, String f, String l) {
            calls++;
            audio.set(a);
            filename.set(f);
            language.set(l);
            if (failure != null) throw failure;
            return text;
        }
    }

    private StubGate gate;
    private StubWhisper whisper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gate = new StubGate();
        whisper = new StubWhisper();
        mvc = build(1024 * 1024);
    }

    private MockMvc build(long maxBytes) {
        return MockMvcBuilders.standaloneSetup(new VoiceController(whisper, gate, maxBytes)).build();
    }

    private static MockMultipartFile clip(String name, byte[] bytes) {
        return new MockMultipartFile("audio", name, "audio/webm", bytes);
    }

    // ── GET /status ──────────────────────────────────────────────────────────

    @Test
    void statusReportsAvailableAndEnabled() throws Exception {
        mvc.perform(get("/api/voice/status").header("Authorization", "Bearer t").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.enabled").value(true));
        assertEquals("Bearer t", gate.bearer.get());
        assertEquals("acme", gate.tenant.get());
    }

    @Test
    void statusShowsDeploymentAndTenantHalvesIndependently() throws Exception {
        whisper.reachable = false;
        mvc.perform(get("/api/voice/status"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.enabled").value(true));

        whisper.reachable = true;
        gate.enabled = false;
        mvc.perform(get("/api/voice/status"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void statusWorksWithoutAuthorizationHeaders() throws Exception {
        mvc.perform(get("/api/voice/status")).andExpect(status().isOk());
        assertNull(gate.bearer.get());
        assertNull(gate.tenant.get());
    }

    // ── POST /transcribe: gating & validation ────────────────────────────────

    @Test
    void transcribeForbiddenWhenTenantNotEnabled() throws Exception {
        gate.enabled = false;
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[]{1})))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Voice is not enabled for your organisation."));
        assertEquals(0, whisper.calls);
    }

    @Test
    void gateIsCheckedBeforeAudioIsValidated() throws Exception {
        gate.enabled = false;
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.exe", new byte[0])))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyAudioIsRejected() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No audio was received."));
        assertEquals(0, whisper.calls);
    }

    @Test
    void oversizedClipIsRejectedWithLimitInMessage() throws Exception {
        MockMvc small = build(2L * 1024 * 1024);
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        small.perform(multipart("/api/voice/transcribe").file(clip("a.webm", big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("2 MB")));
        assertEquals(0, whisper.calls);
    }

    @Test
    void clipExactlyAtLimitIsAccepted() throws Exception {
        MockMvc small = build(16);
        small.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[16])))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedExtensionIsRejected() throws Exception {
        for (String name : new String[]{"clip.exe", "clip.txt", "clip", "clip."}) {
            mvc.perform(multipart("/api/voice/transcribe").file(clip(name, new byte[]{1})))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Unsupported audio format."));
        }
        assertEquals(0, whisper.calls);
    }

    @Test
    void everyAllowedExtensionIsAccepted() throws Exception {
        for (String ext : new String[]{"webm", "ogg", "oga", "opus", "m4a", "mp4", "mp3", "wav", "flac", "aac", "amr", "3gp"}) {
            mvc.perform(multipart("/api/voice/transcribe").file(clip("clip." + ext, new byte[]{1})))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void extensionCheckIsCaseInsensitive() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("CLIP.WEBM", new byte[]{1})))
                .andExpect(status().isOk());
    }

    @Test
    void missingAudioPartIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe")).andExpect(status().isBadRequest());
    }

    // ── POST /transcribe: success path ───────────────────────────────────────

    @Test
    void transcribeReturnsTextAndPassesBytesFilenameAndLanguage() throws Exception {
        byte[] bytes = {9, 8, 7};
        mvc.perform(multipart("/api/voice/transcribe").file(clip("my.mp3", bytes)).param("language", "de")
                        .header("Authorization", "Bearer t").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("hello world"));
        assertArrayEquals(bytes, whisper.audio.get());
        assertEquals("my.mp3", whisper.filename.get());
        assertEquals("de", whisper.language.get());
        assertEquals("Bearer t", gate.bearer.get());
        assertEquals("acme", gate.tenant.get());
    }

    @Test
    void languageIsOptional() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[]{1})))
                .andExpect(status().isOk());
        assertNull(whisper.language.get());
    }

    @Test
    void emptyTranscriptIsReturnedAsEmptyTextNotAnError() throws Exception {
        whisper.text = "";
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""));
    }

    // ── filename sanitising ──────────────────────────────────────────────────

    @Test
    void pathComponentsAreStrippedFromFilename() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("../../etc/passwd.wav", new byte[]{1})))
                .andExpect(status().isOk());
        assertEquals("passwd.wav", whisper.filename.get());

        mvc.perform(multipart("/api/voice/transcribe").file(clip("C:\\Users\\me\\rec.ogg", new byte[]{1})))
                .andExpect(status().isOk());
        assertEquals("rec.ogg", whisper.filename.get());
    }

    @Test
    void unsafeCharactersInFilenameAreReplaced() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("my clip;\"x\".webm", new byte[]{1})))
                .andExpect(status().isOk());
        assertEquals("my_clip__x_.webm", whisper.filename.get());
    }

    @Test
    void blankFilenameFallsBackToWebm() throws Exception {
        mvc.perform(multipart("/api/voice/transcribe").file(clip("", new byte[]{1})))
                .andExpect(status().isOk());
        assertEquals("clip.webm", whisper.filename.get());
    }

    // ── failure mapping ──────────────────────────────────────────────────────

    @Test
    void transcriptionExceptionMapsTo502WithItsMessage() throws Exception {
        whisper.failure = new WhisperClient.TranscriptionException("The speech recogniser rejected that clip.");
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[]{1})))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("The speech recogniser rejected that clip."));
    }

    @Test
    void unexpectedExceptionMapsTo500WithGenericMessage() throws Exception {
        whisper.failure = new IllegalStateException("internal detail that must not leak");
        mvc.perform(multipart("/api/voice/transcribe").file(clip("a.webm", new byte[]{1})))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Speech recognition failed. Please try again."));
    }
}
