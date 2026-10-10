package io.orphos.eip.docingest.search;

/**
 * Request body for POST /api/doc-ingest/search.
 *
 * <p>{@code query} is required. Every other field is an optional per-request
 * override; when null the controller falls back to the tenant's saved
 * {@link io.orphos.eip.docingest.settings.DocSettings} search settings. The
 * legacy {@code limit} field is still honoured (maps to {@code maxCitations}).</p>
 */
public record SearchRequest(
    String  query,
    int     limit,              // legacy; 0 = unset → use settings/topK
    Integer topK,
    Integer maxCitations,
    String  retrievalMode,      // DENSE | HYBRID | SERVER_DEFAULT
    Boolean rerank,
    String  passageMatching,    // SEMANTIC | EXACT_PHRASE | ALL_KEYWORDS
    Boolean caseSensitive,
    Boolean ignoreWhitespace,
    String  documentType,
    String  effectiveAfter,     // ISO date yyyy-MM-dd
    String  language,
    String  model
) {}
