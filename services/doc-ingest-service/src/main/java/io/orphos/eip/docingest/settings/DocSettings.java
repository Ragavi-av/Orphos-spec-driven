package io.orphos.eip.docingest.settings;

/**
 * Per-tenant document-ingestion and search configuration, edited by tenant
 * admins (or the scoped {@code ingestion_admin} role) from the AdminPage
 * "Ingestion Defaults" and "Search Settings" tabs.
 *
 * <p>Persisted as a single JSON blob per tenant in the {@code DocIngestSettings}
 * ArcadeDB document type. All fields are nullable on the wire; {@link #normalized()}
 * fills blanks with {@link #defaults()} so downstream code never sees a null.</p>
 */
public record DocSettings(IngestionDefaults ingestion, SearchSettings search) {

    /**
     * Defaults applied to ingested documents when the uploader leaves a field
     * blank. {@code organizeBy} drives the physical storage layout under the
     * configured storage root (see {@code LocalDocumentStorage}).
     * {@code aiMetadata} opt-in enables the LLM metadata enrichment step
     * (DocumentAiEnricher) that classifies documents and fills blank
     * type/category/department/title fields at ingestion time.
     */
    public record IngestionDefaults(
        String defaultCategory,
        String defaultDepartment,
        String defaultFolder,
        String organizeBy,           // NONE | DEPARTMENT | FILETYPE | DEPARTMENT_FILETYPE
        boolean aiMetadata,
        String storageConnectionId   // "" = bundled local storage; else a folder-connection id (S3/FTP/…)
    ) {}

    /**
     * Search behaviour. Most fields drive passage retrieval directly
     * ({@code topK}, {@code maxCitations}, {@code retrievalMode}, filters,
     * matching). {@code answerLanguage} and {@code model} are forwarded to the
     * answer-generation layer (copilot / nl-query) — passage search itself does
     * not synthesise an answer, so they are stored and echoed, not applied to
     * retrieval.
     */
    public record SearchSettings(
        String  answerLanguage,     // "auto" or an ISO language code (en, ta, …)
        String  model,              // "server-default" or a provider model id
        String  passageMatching,    // SEMANTIC | EXACT_PHRASE | ALL_KEYWORDS
        boolean caseSensitive,
        boolean ignoreWhitespace,
        String  retrievalMode,      // SERVER_DEFAULT | DENSE | HYBRID
        int     retrievalTopK,
        int     maxCitations,
        boolean crossEncoderRerank,
        String  documentTypeFilter, // "" / "Any" = no filter
        String  effectiveAfter      // ISO date (yyyy-MM-dd) or "" = no filter
    ) {}

    public static DocSettings defaults() {
        return new DocSettings(
            new IngestionDefaults("", "", "", "DEPARTMENT_FILETYPE", false, ""),
            new SearchSettings(
                "auto", "server-default", "SEMANTIC",
                false, true,
                "SERVER_DEFAULT", 20, 5, true,
                "", ""
            )
        );
    }

    /** Returns a copy with every null/invalid field replaced by the default. */
    public DocSettings normalized() {
        DocSettings d = defaults();
        IngestionDefaults i = ingestion != null ? ingestion : d.ingestion();
        SearchSettings s    = search    != null ? search    : d.search();

        IngestionDefaults ni = new IngestionDefaults(
            nz(i.defaultCategory(),   d.ingestion().defaultCategory()),
            nz(i.defaultDepartment(), d.ingestion().defaultDepartment()),
            nz(i.defaultFolder(),     d.ingestion().defaultFolder()),
            nz(i.organizeBy(),        d.ingestion().organizeBy()),
            i.aiMetadata(),
            i.storageConnectionId() != null ? i.storageConnectionId() : ""
        );

        SearchSettings ns = new SearchSettings(
            nz(s.answerLanguage(),    d.search().answerLanguage()),
            nz(s.model(),             d.search().model()),
            nz(s.passageMatching(),   d.search().passageMatching()),
            s.caseSensitive(),
            s.ignoreWhitespace(),
            nz(s.retrievalMode(),     d.search().retrievalMode()),
            clamp(s.retrievalTopK(),  1, 50,  d.search().retrievalTopK()),
            clamp(s.maxCitations(),   1, 50,  d.search().maxCitations()),
            s.crossEncoderRerank(),
            s.documentTypeFilter() != null ? s.documentTypeFilter() : "",
            s.effectiveAfter()     != null ? s.effectiveAfter()     : ""
        );
        return new DocSettings(ni, ns);
    }

    private static String nz(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int clamp(int v, int lo, int hi, int fallback) {
        if (v < lo || v > hi) return fallback;
        return v;
    }
}
