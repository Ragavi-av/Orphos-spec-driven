package io.orphos.eip.voice.client;

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
import java.time.Duration;

/**
 * Thin client for the Whisper ASR sidecar (openai-whisper-asr-webservice,
 * {@code eip-whisper}).
 *
 * <p>Unlike doc-ingest's {@code TranscriptionClient}, which reads an audio file
 * off disk as part of the ingestion pipeline, this one takes the bytes of a clip
 * the browser just recorded and is on the user's critical path — so a failure
 * here is reported to the caller rather than swallowed. A user who has just
 * spoken a sentence needs to be told it did not work; an ingestion job can
 * simply skip the file.</p>
 */
@Component
public class WhisperClient {

    private static final Logger log = LogManager.getLogger(WhisperClient.class);

    /** Raised when the transcript could not be produced; the message is user-facing. */
    public static class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message) { super(message); }
    }

    private final String url;
    private final Duration timeout;
    private final HttpClient http;

    public WhisperClient(
            @Value("${voice.whisper.url:http://eip-whisper:9000}") String url,
            @Value("${voice.whisper.timeout-seconds:120}") int timeoutSeconds) {
        this.url = url == null ? "" : url.replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(Math.max(15, timeoutSeconds));
        // HTTP/1.1 is mandatory: the JDK client defaults to HTTP_2, which on a cleartext
        // http:// URL opens with an h2c upgrade. Whisper runs on uvicorn/h11, which rejects
        // the upgrade ("Unsupported upgrade request") and loses the request body with it —
        // FastAPI then sees no audio_file and answers 422, so every transcription silently
        // degrades to "no text". Learned the hard way in doc-ingest's TranscriptionClient;
        // do not "simplify" this back to HttpClient.newHttpClient().
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** True once a Whisper URL is configured at all. */
    public boolean isConfigured() {
        return !url.isEmpty();
    }

    /**
     * Whether the sidecar is reachable right now. Used by {@code /api/voice/status}
     * so the SPA shows the voice affordance only when dictation would actually work,
     * and by the admin tab to report why it would not.
     */
    public boolean isReachable() {
        if (!isConfigured()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/docs"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 || status == 404;   // 404 still proves something is listening
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Transcribe a recorded clip to plain text.
     *
     * @param audio    the raw clip bytes as the browser recorded them
     * @param filename original name — the EXTENSION matters, because Whisper's ffmpeg
     *                 decode path picks its demuxer from it; a name with no extension
     *                 is sniffed as raw PCM and comes back as noise
     * @param language optional ISO-639-1 hint; null lets Whisper auto-detect
     * @throws TranscriptionException with a user-facing message on any failure
     */
    public String transcribe(byte[] audio, String filename, String language) {
        if (!isConfigured()) {
            throw new TranscriptionException("Voice transcription is not configured.");
        }
        String endpoint = url + "/asr?encode=true&task=transcribe&output=txt"
                + (language != null && !language.isBlank() ? "&language=" + language.trim() : "");
        try {
            String boundary = "----orphos" + System.nanoTime();
            byte[] body = multipartBody(boundary, "audio_file", filename, audio);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                String detail = res.body() == null ? "" : res.body().trim();
                log.warn("Whisper returned HTTP {} for a {}-byte clip ({})",
                        res.statusCode(), audio.length,
                        detail.length() > 300 ? detail.substring(0, 300) + "…" : detail);
                throw new TranscriptionException("The speech recogniser rejected that clip.");
            }
            String text = res.body() == null ? "" : res.body().trim();
            if (text.isEmpty()) {
                // Not an error: silence, or speech below the noise floor. The caller
                // turns this into "no speech recognised", which is the honest message.
                return "";
            }
            return text;
        } catch (TranscriptionException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Whisper timed out after {}s on a {}-byte clip", timeout.toSeconds(), audio.length);
            throw new TranscriptionException("Transcription took too long. Try a shorter recording.");
        } catch (Exception e) {
            log.warn("Transcription failed ({})", e.getMessage());
            throw new TranscriptionException("Speech recognition is unavailable right now.");
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
            throw new TranscriptionException("Could not package the audio for transcription.");
        }
    }
}
