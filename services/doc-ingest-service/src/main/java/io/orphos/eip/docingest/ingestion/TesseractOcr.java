package io.orphos.eip.docingest.ingestion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Whole-page OCR of a photo with the Tesseract binary installed in the container.
 *
 * <p>Photos skip Docling's layout model here first: it classifies a photographed
 * page as a picture and OCRs only fragments of it, and its RapidOCR "japan" model
 * drops the spaces between Latin words ("OcRnotworking"). Tesseract on the upright
 * image reads printed English and Japanese cleanly. The caller keeps Docling as
 * the fallback when this returns nothing readable.</p>
 *
 * <p>Best-effort: returns {@code null} when disabled, not an image, or on any
 * failure, so parsing never hard-fails on OCR alone.</p>
 */
@Component
public class TesseractOcr {

    private static final Logger log = LogManager.getLogger(TesseractOcr.class);

    private final boolean enabled;
    private final String languages;
    private final Set<String> formats;
    private final int timeoutSeconds;

    public TesseractOcr(
            @Value("${doc-ingest.photo-ocr.enabled:true}") boolean enabled,
            @Value("${doc-ingest.photo-ocr.languages:eng+jpn}") String languages,
            @Value("${doc-ingest.photo-ocr.formats:png,jpg,jpeg,tif,tiff,bmp,webp}") String formats,
            @Value("${doc-ingest.photo-ocr.timeout-seconds:60}") int timeoutSeconds) {
        this.enabled = enabled;
        this.languages = languages == null || languages.isBlank() ? "eng" : languages.trim();
        this.formats = Arrays.stream(formats.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.timeoutSeconds = timeoutSeconds;
    }

    /** True when {@code file} is an image this OCR should read (judged by its original name). */
    public boolean handles(Path file) {
        return enabled && formats.contains(extension(file));
    }

    /** The page's text, or {@code null} when it could not be read. */
    public String read(Path image) {
        if (!enabled) return null;
        Path out = null;
        try {
            out = Files.createTempFile("doc-ocr-", ".txt");
            // "stdout" would mix Tesseract's warnings into the text; write to a file instead.
            Path base = out.resolveSibling(out.getFileName().toString().replaceFirst("\\.txt$", ""));
            Process p = new ProcessBuilder(List.of("tesseract", image.toString(), base.toString(),
                    "-l", languages, "--psm", "3"))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Tesseract timed out after {}s on {}", timeoutSeconds, image.getFileName());
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("Tesseract exited {} on {}", p.exitValue(), image.getFileName());
                return null;
            }
            String text = Files.readString(out, StandardCharsets.UTF_8).replaceAll("\\n{3,}", "\n\n").strip();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            log.warn("Tesseract unavailable for {} ({})", image.getFileName(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // temp file; the OS cleans it up
                }
            }
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : "";
    }
}
