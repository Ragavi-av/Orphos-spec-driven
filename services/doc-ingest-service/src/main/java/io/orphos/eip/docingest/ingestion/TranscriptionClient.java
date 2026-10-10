package io.orphos.eip.docingest.ingestion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Speech-to-text for audio uploads, backed by the Whisper ASR sidecar
 * (openai-whisper-asr-webservice, {@code eip-whisper}). Turns a voice note or any
 * audio file into a transcript so the rest of the doc-ingest pipeline (chunk →
 * embed → Qdrant) is reused unchanged — the audio becomes searchable text.
 *
 * <p>Best-effort by design, exactly like {@link DoclingParser}: any failure
 * (sidecar down, unsupported codec, empty result) returns {@code null} so the
 * caller can fall back / mark the item appropriately rather than hard-failing.
 * Runs fully offline against the bundled Whisper container.
 */
@Component
public class TranscriptionClient {

    private static final Logger log = LogManager.getLogger(TranscriptionClient.class);

    private final boolean enabled;
    private final String url;
    private final Set<String> formats;
    private final HttpClient http;

    public TranscriptionClient(
            @Value("${doc-ingest.whisper.enabled:true}") boolean enabled,
            @Value("${doc-ingest.whisper.url:http://eip-whisper:9000}") String url,
            @Value("${doc-ingest.whisper.formats:mp3,wav,m4a,mp4,aac,ogg,oga,opus,flac,webm,amr,3gp}") String formats,
            @Value("${doc-ingest.whisper.timeout-seconds:300}") int timeoutSeconds) {
        this.enabled = enabled;
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.formats = Arrays.stream(formats.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        // HTTP/1.1 is mandatory here: the JDK client defaults to HTTP_2, which on a
        // cleartext http:// URL opens with an h2c upgrade. Whisper runs on uvicorn/h11,
        // which rejects the upgrade ("Unsupported upgrade request") and loses the
        // request body with it — FastAPI then sees no audio_file and answers 422, so
        // every transcription silently degrades to "no text" and the doc is SKIPPED.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.timeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
        if (enabled) {
            log.info("Audio transcription ENABLED (whisper url={}, formats={})", this.url, this.formats);
        }
    }

    private final Duration timeout;

    /** True when this file is an audio type transcription should handle. */
    public boolean handles(Path file) {
        return enabled && !url.isEmpty() && formats.contains(extension(file));
    }

    /**
     * Transcribe {@code file} to plain text via the Whisper sidecar, or {@code null}
     * on any failure. Uses the ASR endpoint's {@code output=txt} mode so the body is
     * the transcript verbatim.
     */
    public String transcribe(Path file) {
        if (!handles(file)) return null;
        try {
            byte[] audio = Files.readAllBytes(file);
            String boundary = "----orphos" + System.nanoTime();
            byte[] body = multipartBody(boundary, "audio_file", file.getFileName().toString(), audio);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/asr?encode=true&task=transcribe&output=txt"))
                    .timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                // Include the body: Whisper's FastAPI errors name the offending field,
                // which is the difference between a diagnosable failure and a silent skip.
                String detail = res.body() == null ? "" : res.body().trim();
                log.warn("Whisper returned HTTP {} for {} — audio not transcribed ({})",
                        res.statusCode(), file.getFileName(),
                        detail.length() > 300 ? detail.substring(0, 300) + "…" : detail);
                return null;
            }
            String text = res.body() == null ? "" : res.body().trim();
            if (text.isEmpty()) {
                log.warn("Whisper produced an empty transcript for {}", file.getFileName());
                return null;
            }
            return text;
        } catch (Exception e) {
            log.warn("Transcription failed for {} ({})", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private static byte[] multipartBody(String boundary, String field, String filename, byte[] content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build multipart body: " + e.getMessage(), e);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : "";
    }
}
