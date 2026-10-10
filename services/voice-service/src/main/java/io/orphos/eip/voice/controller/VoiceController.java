package io.orphos.eip.voice.controller;

import io.orphos.eip.voice.client.WhisperClient;
import io.orphos.eip.voice.service.VoiceFeatureGate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Browser-facing speech-to-text for Ask Orphos.
 *
 * <p>Two endpoints, both tenant-gated:</p>
 * <ul>
 *   <li>{@code GET /api/voice/status} — may this caller dictate? The SPA branches
 *       its ORB variant on this and nothing else.</li>
 *   <li>{@code POST /api/voice/transcribe} — a recorded clip in, a transcript out.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger log = LogManager.getLogger(VoiceController.class);

    /**
     * Container formats the browser's MediaRecorder can produce, plus the common
     * upload types. Checked on the FILENAME because that is what Whisper's ffmpeg
     * uses to pick a demuxer — an unknown extension is decoded as raw PCM and comes
     * back as plausible-looking nonsense rather than as an error.
     */
    private static final Set<String> ALLOWED_EXT =
            Set.of("webm", "ogg", "oga", "opus", "m4a", "mp4", "mp3", "wav", "flac", "aac", "amr", "3gp");

    private final WhisperClient whisper;
    private final VoiceFeatureGate gate;
    private final long maxBytes;

    public VoiceController(WhisperClient whisper,
                           VoiceFeatureGate gate,
                           @Value("${voice.max-clip-bytes:26214400}") long maxBytes) {
        this.whisper = whisper;
        this.gate = gate;
        this.maxBytes = maxBytes;
    }

    /**
     * Whether dictation is live for the calling tenant.
     *
     * <p>{@code available} is about this deployment (the Whisper sidecar is up),
     * {@code enabled} is about this tenant (a platform admin turned it on). The
     * SPA needs both to be true; the admin tab shows them apart so an operator
     * can see which half is missing.</p>
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", whisper.isReachable());
        out.put("enabled", gate.isEnabled(bearer, tenantHeader));
        return out;
    }

    /**
     * Transcribe a recorded clip.
     *
     * <p>The audio is held in memory and dropped as soon as the transcript is
     * produced — nothing is written to disk and nothing is persisted here. A voice
     * clip is more personal than the text it becomes, and this service has no
     * reason to keep it. (Audio that the user deliberately UPLOADS as a document
     * is a different matter and goes through doc-ingest, which stores it.)</p>
     */
    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {

        if (!gate.isEnabled(bearer, tenantHeader)) {
            // 403 rather than 404: the route exists, this tenant may not use it.
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Voice is not enabled for your organisation."));
        }
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No audio was received."));
        }
        if (audio.getSize() > maxBytes) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "That recording is too long. Keep it under " + (maxBytes / (1024 * 1024)) + " MB."));
        }

        String filename = sanitize(audio.getOriginalFilename());
        if (!ALLOWED_EXT.contains(extension(filename))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported audio format."));
        }

        try {
            byte[] bytes = audio.getBytes();
            long started = System.currentTimeMillis();
            String text = whisper.transcribe(bytes, filename, language);
            // Length and duration only — never the transcript itself. This is a log,
            // and what the user dictated is their business.
            log.info("Transcribed a {}-byte {} clip in {} ms ({} chars)",
                    bytes.length, extension(filename), System.currentTimeMillis() - started, text.length());
            return ResponseEntity.ok(Map.of("text", text));
        } catch (WhisperClient.TranscriptionException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Unexpected transcription failure ({})", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Speech recognition failed. Please try again."));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Strips any path the browser may have supplied and keeps a plain, safe name. */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "clip.webm";
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.isBlank() ? "clip.webm" : base;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot + 1 < filename.length()
                ? filename.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }
}
