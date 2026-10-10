package io.orphos.eip.docingest.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.docingest.ingestion.DocumentMetadata;
import io.orphos.eip.docingest.settings.DocSettingsService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Best-effort LLM metadata enrichment for the ingestion pipeline. When the
 * tenant opts in (Administration → Document Ingestion → Document AI), asks the
 * tenant's configured AI provider (via nl-query) to classify the parsed
 * document and fills ONLY the metadata fields the uploader left blank —
 * documentType, category, department and title. Any failure leaves the
 * metadata untouched so ingestion never breaks.
 */
@Component
public class DocumentAiEnricher {

    private static final Logger log = LogManager.getLogger(DocumentAiEnricher.class);

    /** Usage type registered in nl-query's AiUsageTypes — lets the tenant admin
     *  pin a provider/model to document enrichment in the AI Providers tab. */
    static final String USAGE_TYPE = "document-processing";

    /**
     * Prompt key in nl-query's admin-editable store (kind doc-processing).
     * The bundled template below must stay in sync with the seed in nl-query's
     * default-prompts.yml.
     */
    static final String PROMPT_KEY = "doc-processing.metadata";

    /** Enough context to classify without blowing small local models' windows. */
    private static final int MAX_EXCERPT_CHARS = 4_000;
    private static final int MAX_FIELD_CHARS   = 120;

    private static final String DEFAULT_TEMPLATE = """
            You are a records-management assistant classifying a document that was just
            uploaded into an enterprise document store. Reply with ONLY a compact JSON
            object, no prose, no markdown fences.

            Schema:
            {
              "documentType": "one of: Policy, Report, Contract, Invoice, Manual, Form, Memo, Circular — or a better short label",
              "category": "short business category, e.g. Policy, Finance Report",
              "department": "the owning department if evident, e.g. Finance, HR, Legal, Operations, IT — else empty string",
              "title": "a clean human-readable document title"
            }

            Rules:
            - Base every field ONLY on the document content below; use an empty string when unsure.
            - Write "title" in the SAME language as the document.
            - Keep values short — these are metadata fields, not prose.

            Document:
            - file name: {{FILE_NAME}}
            - content (excerpt):
            {{EXCERPT}}
            """;

    private final NlQueryClient nlQuery;
    private final DocSettingsService settingsService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DocumentAiEnricher(NlQueryClient nlQuery, DocSettingsService settingsService) {
        this.nlQuery = nlQuery;
        this.settingsService = settingsService;
    }

    /**
     * Fills blank metadata fields from an LLM classification of the parsed
     * text. Returns true when at least one field was filled (so the caller
     * knows to persist the updated metadata).
     */
    public boolean enrich(DocumentMetadata metadata, String rawText) {
        if (!nlQuery.isEnabled()) return false;
        String tenantId = metadata.getTenantId();
        if (tenantId == null || tenantId.isBlank()) return false;
        if (!settingsService.get(tenantId).ingestion().aiMetadata()) return false;

        boolean needsType  = isBlank(metadata.getDocumentType());
        boolean needsCat   = isBlank(metadata.getCategory());
        boolean needsDept  = isBlank(metadata.getDepartment());
        boolean needsTitle = isBlank(metadata.getTitle());
        if (!needsType && !needsCat && !needsDept && !needsTitle) return false;

        String prompt = nlQuery.promptText(tenantId, PROMPT_KEY).orElse(DEFAULT_TEMPLATE)
                .replace("{{FILE_NAME}}", safe(metadata.getFileName()))
                .replace("{{EXCERPT}}",   excerpt(rawText));

        var completion = nlQuery.complete(tenantId, prompt, USAGE_TYPE);
        if (completion.isEmpty()) return false;

        try {
            JsonNode json = parseJson(completion.get());
            if (json == null) return false;

            boolean changed = false;
            if (needsType)  changed |= apply(json, "documentType", metadata::setDocumentType);
            if (needsCat)   changed |= apply(json, "category",     metadata::setCategory);
            if (needsDept)  changed |= apply(json, "department",   metadata::setDepartment);
            if (needsTitle) changed |= apply(json, "title",        metadata::setTitle);
            if (changed) {
                log.info("AI metadata enrichment applied for document {} [tenant={}]",
                        metadata.getDocumentId(), tenantId);
            }
            return changed;
        } catch (Exception e) {
            log.debug("AI metadata enrichment parse failed for {}: {}",
                    metadata.getDocumentId(), e.getMessage());
            return false;
        }
    }

    private static boolean apply(JsonNode json, String field, java.util.function.Consumer<String> setter) {
        String v = json.path(field).asText("").trim();
        if (v.isEmpty()) return false;
        if (v.length() > MAX_FIELD_CHARS) v = v.substring(0, MAX_FIELD_CHARS);
        setter.accept(v);
        return true;
    }

    /** Tolerant JSON extraction — strips markdown fences and locates the object. */
    private JsonNode parseJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return mapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() > MAX_EXCERPT_CHARS ? t.substring(0, MAX_EXCERPT_CHARS) + "…" : t;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String safe(String s) { return s == null ? "" : s; }
}
