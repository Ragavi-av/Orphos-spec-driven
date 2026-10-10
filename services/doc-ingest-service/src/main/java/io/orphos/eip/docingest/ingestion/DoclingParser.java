package io.orphos.eip.docingest.ingestion;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.OcrEngine;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Document parser backed by the Docling sidecar (docling-serve) via the official
 * docling-java client. Docling does layout-aware parsing (reading order, tables,
 * multi-column) and pluggable OCR — markedly better than Tika on complex and
 * scanned Japanese documents.
 *
 * <p>This service keeps ZERO Python: all parsing happens in the docling-serve
 * container; here we only call its REST API. Output is requested as HTML so it
 * can be fed straight into {@link io.orphos.eip.docingest.ingestion.structure.StructuredDocumentParser#parseXhtml}
 * and reuse the existing block/semantic-chunk pipeline.
 *
 * <p>Best-effort by design: {@link #parse(Path)} returns {@code null} on any
 * failure (sidecar down, conversion error, empty output) so the caller falls
 * back to Tika and ingestion never hard-fails on Docling alone.
 *
 * <p>Compile note: the docling-serve-client artifact is fetched from Maven
 * Central at build time (not in the offline repo). The accessor/option method
 * names below follow the documented 0.6.1 API.
 */
@Component
public class DoclingParser {

    private static final Logger log = LogManager.getLogger(DoclingParser.class);

    private final boolean ocr;
    private final String ocrEngine;
    private final String ocrLang;
    private final Set<String> formats;
    /** Null when Docling is disabled — every entry point guards on this. */
    private final DoclingServeApi api;

    public DoclingParser(
            @Value("${doc-ingest.docling.enabled:false}") boolean enabled,
            @Value("${doc-ingest.docling.url:http://eip-docling:5001}") String url,
            @Value("${doc-ingest.docling.formats:pdf,png,jpg,jpeg,tif,tiff}") String formats,
            @Value("${doc-ingest.docling.ocr:true}") boolean ocr,
            @Value("${doc-ingest.docling.ocr-engine:}") String ocrEngine,
            @Value("${doc-ingest.docling.ocr-lang:}") String ocrLang) {
        this.ocr = ocr;
        this.ocrEngine = ocrEngine == null ? "" : ocrEngine.trim();
        this.ocrLang = ocrLang == null ? "" : ocrLang.trim();
        this.formats = Arrays.stream(formats.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.api = enabled ? DoclingServeApi.builder().baseUrl(url).build() : null;
        if (enabled) {
            log.info("Docling parsing ENABLED (url={}, formats={}, ocr={}, engine='{}', lang='{}')",
                    url, this.formats, ocr, this.ocrEngine, this.ocrLang);
        }
    }

    /** True when Docling should handle this file (enabled AND extension in the configured set). */
    public boolean handles(Path file) {
        return api != null && formats.contains(extension(file));
    }

    /**
     * Converts {@code file} via docling-serve to structural HTML.
     *
     * @return the conversion result, or {@code null} on any failure (caller falls back to Tika).
     */
    public Result parse(Path file) {
        if (api == null) return null;
        try {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));

            var options = ConvertDocumentOptions.builder().toFormat(OutputFormat.HTML);
            if (ocr) {
                options.doOcr(true);
                OcrEngine engine = parseEngine(ocrEngine);
                if (engine != null) options.ocrEngine(engine);
                if (!ocrLang.isEmpty()) options.ocrLang(ocrLang);
            }

            ConvertDocumentRequest request = ConvertDocumentRequest.builder()
                    .source(FileSource.builder()
                            .filename(file.getFileName().toString())
                            .base64String(base64)
                            .build())
                    .options(options.build())
                    .target(InBodyTarget.builder().build())
                    .build();

            InBodyConvertDocumentResponse response =
                    (InBodyConvertDocumentResponse) api.convertSource(request);

            String html = response.getDocument().getHtmlContent();
            if (html == null || html.isBlank()) {
                log.warn("Docling returned empty output for {} — falling back to Tika", file.getFileName());
                return null;
            }
            return new Result(html);
        } catch (Exception e) {
            log.warn("Docling parse failed for {} ({}) — falling back to Tika",
                    file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Maps the configured engine name to the OcrEngine enum; null when blank/unknown (server default). */
    private OcrEngine parseEngine(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return OcrEngine.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown Docling OCR engine '{}' — using the server default", name);
            return null;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : "";
    }

    /** Docling conversion output: structural HTML for the XHTML→Block pipeline. */
    public record Result(String html) {}
}
