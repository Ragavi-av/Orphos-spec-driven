package io.orphos.eip.decision.component;

import io.orphos.eip.decision.exception.DecisionParseException;
import io.orphos.eip.decision.util.DecisionDocumentParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Extracts plain text from binary decision documents (PDF, Word, etc.) so
 * {@link DecisionDocumentParser} can take over — the multi-format leg of
 * Req 12.5. Tika's AutoDetectParser covers PDF, DOCX, XLSX, PPTX, HTML and
 * plain text, the same stack doc-ingest uses for its fallback parsing.
 */
@Component
public class DocumentTextExtractor {

    private static final int MAX_CHARS = 500_000;

    /** Content types (or filename suffixes) that are already text and skip Tika. */
    private static final List<String> TEXT_SUFFIXES = List.of(".md", ".markdown", ".txt", ".json");

    public boolean isTextual(String fileName, String contentType) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (TEXT_SUFFIXES.stream().anyMatch(name::endsWith)) return true;
        String ct = contentType == null ? "" : contentType.toLowerCase();
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("markdown");
    }

    public String extract(byte[] bytes, String fileName) {
        try {
            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            Metadata metadata = new Metadata();
            if (fileName != null && !fileName.isBlank()) {
                metadata.set("resourceName", fileName);
            }
            new AutoDetectParser().parse(new ByteArrayInputStream(bytes), handler, metadata);
            return handler.toString();
        } catch (Exception e) {
            throw new DecisionParseException(List.of(new DecisionParseException.Issue(0,
                "Could not extract text from '" + fileName + "': " + e.getMessage())));
        }
    }
}
