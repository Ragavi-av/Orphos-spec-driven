package io.orphos.eip.docingest.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.docingest.ingestion.DocumentRegistry;
import io.orphos.eip.docingest.settings.DocSettings;
import io.orphos.eip.docingest.settings.DocSettingsService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Semantic search over ingested documents.
 *
 * POST /api/doc-ingest/search            — authenticated, any role
 * GET  /api/doc-ingest/admin/search/history — admin only
 */
@RestController
@RequestMapping("/api/doc-ingest")
public class DocSearchController {

    private static final Logger log = LogManager.getLogger(DocSearchController.class);
    private static final int MAX_LIMIT    = 20;

    // Strict cosine floor for a match admitted on SEMANTIC similarity alone.
    // Measured against bge-m3 on this corpus: an on-topic multi-word query tops
    // out around 0.61, while a correct but terse query ("POSH") scores ~0.31 —
    // so the old 0.50 floor silently rejected every short query. 0.45 keeps
    // genuine prose matches while still excluding generic corporate boilerplate.
    @org.springframework.beans.factory.annotation.Value("${doc-ingest.search.min-score:0.45}")
    private double minScore;

    // Recall floor handed to the vector store. Deliberately far below minScore so
    // a chunk that matches the query LEXICALLY (an acronym, a document name, a
    // clause number) still reaches the keyword stage, which is precisely where
    // dense similarity is weakest. Candidates that clear only this floor must
    // earn their place via lexical coverage below — a low dense score on its own
    // is never enough.
    @org.springframework.beans.factory.annotation.Value("${doc-ingest.search.recall-min-score:0.25}")
    private double recallMinScore;

    // Fraction of the query's distinct content tokens that must appear in a
    // chunk's searchable text for it to count as a lexical match. 0.5 means "at
    // least half the query's terms are literally present" — enough for a
    // one-word query like "POSH" to pull in its own document, and strict enough
    // that a single shared generic word ("policy") does not pull in every other.
    @org.springframework.beans.factory.annotation.Value("${doc-ingest.search.lexical-coverage:0.5}")
    private double lexicalCoverage;

    // A query term appearing in more than this fraction of the candidate pool is
    // too common to distinguish one document from another, so it casts no lexical
    // vote. This is what stops "policy" — a word in every document of a policy
    // corpus — from admitting the entire corpus on a search for "POSH Policy".
    @org.springframework.beans.factory.annotation.Value("${doc-ingest.search.max-doc-freq-ratio:0.8}")
    private double maxDocFreqRatio;

    // bge-m3 needs NO query prefix (unlike nomic-embed-text, which required
    // "search_query: "). Kept as a knob for a future model swap; default empty.
    // Must stay consistent with doc-ingest.embedding.document-prefix at ingest.
    @org.springframework.beans.factory.annotation.Value("${doc-ingest.embedding.query-prefix:}")
    private String queryPrefix;

    // Hard ceiling on candidate over-fetch from the vector store, independent of
    // the configurable per-tenant top-K (1–50).
    private static final int MAX_FETCH = 300;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentRegistry documentRegistry;
    private final DocQueryHistory queryHistory;
    private final DocSettingsService settingsService;
    private final RerankClient rerankClient;

    public DocSearchController(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore,
                                DocumentRegistry documentRegistry,
                                DocQueryHistory queryHistory,
                                DocSettingsService settingsService,
                                RerankClient rerankClient) {
        this.embeddingModel   = embeddingModel;
        this.embeddingStore   = embeddingStore;
        this.documentRegistry = documentRegistry;
        this.queryHistory     = queryHistory;
        this.settingsService  = settingsService;
        this.rerankClient     = rerankClient;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestBody SearchRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        String tenantId = currentTenantId();
        // Store a human-readable identifier (preferred_username / email) rather
        // than the opaque Keycloak sub UUID — doc-ingest has no Keycloak admin
        // resolver, so the history UI shows whatever is persisted here.
        String userSub  = displayName(jwt);
        // The opaque sub, by contrast, is what owned (private) captures are stamped with.
        String callerSub = jwt != null ? jwt.getSubject() : null;
        String query    = req.query() != null ? req.query().trim() : "";

        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query must not be blank"));
        }

        // Effective settings = tenant defaults, overridden per-request where set.
        DocSettings.SearchSettings s = settingsService.get(tenantId).search();
        int     topK            = clamp(firstNonNull(req.topK(), s.retrievalTopK()), 1, 50);
        int     maxCitations    = clamp(firstNonNull(req.maxCitations(),
                                      req.limit() > 0 ? req.limit() : s.maxCitations()), 1, MAX_LIMIT);
        String  retrievalMode   = firstNonBlank(req.retrievalMode(), s.retrievalMode());
        // "Server default" resolves to hybrid (best general default); only an
        // explicit DENSE choice disables keyword fusion.
        boolean hybrid          = "HYBRID".equalsIgnoreCase(retrievalMode)
                                  || "SERVER_DEFAULT".equalsIgnoreCase(retrievalMode);
        boolean rerank          = firstNonNull(req.rerank(), s.crossEncoderRerank());
        String  matching        = firstNonBlank(req.passageMatching(), s.passageMatching());
        boolean caseSensitive   = firstNonNull(req.caseSensitive(), s.caseSensitive());
        boolean ignoreWs        = firstNonNull(req.ignoreWhitespace(), s.ignoreWhitespace());
        String  docTypeFilter   = firstNonBlank(req.documentType(), s.documentTypeFilter());
        String  effectiveAfter  = firstNonBlank(req.effectiveAfter(), s.effectiveAfter());

        long start = System.currentTimeMillis();

        try {
            Response<Embedding> embResp = embeddingModel.embed(TextSegment.from(queryPrefix + query));

            // Over-fetch so tenant + metadata + matching filters still leave a
            // healthy candidate pool. Hybrid fusion benefits from a wide pool too.
            int fetch = Math.min(Math.max(topK, maxCitations) * 6, MAX_FETCH);
            EmbeddingSearchRequest searchReq = EmbeddingSearchRequest.builder()
                .queryEmbedding(embResp.content())
                .maxResults(fetch)
                // The wide recall floor, NOT minScore — see the field comments.
                // Weak-but-lexically-exact matches are separated out in stage 1.
                .minScore(Math.min(recallMinScore, minScore))
                .build();

            EmbeddingSearchResult<TextSegment> raw = embeddingStore.search(searchReq);

            // Stage 1 — reduce the recall pool to the candidates worth ranking.
            //
            // A candidate is kept when it is either a solid semantic match
            // (score >= minScore) OR a literal keyword match on its searchable
            // text (body + section heading + document title/file name). Neither
            // signal alone is reliable: dense retrieval under-scores bare acronyms
            // like "POSH", and keyword matching misses paraphrase.
            //
            // The pool is NOT truncated to topK here — that would hand the keyword
            // and rerank stages a pool already ordered (and cut) by the very
            // signal they exist to correct. It is cut to topK after fusion.

            // Pass 1 — hard filters only (tenant + metadata + explicit matching
            // mode). These are the caller's constraints; nothing about relevance.
            List<EmbeddingMatch<TextSegment>> pool = new ArrayList<>();
            List<String> poolTexts = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : raw.matches()) {
                TextSegment seg = match.embedded();
                if (!tenantId.equals(seg.metadata().getString("tenant_id"))) continue;
                if (!visibleTo(seg, callerSub)) continue;
                if (!matchesDocType(seg, docTypeFilter)) continue;
                if (!matchesEffectiveAfter(seg, effectiveAfter)) continue;
                if (!matchesPassage(seg.text(), query, matching, caseSensitive, ignoreWs)) continue;
                pool.add(match);
                poolTexts.add(LexicalMatcher.searchableText(seg));
            }

            // Pass 2 — the relevance gate, which needs the whole pool in hand: a
            // query term only earns a lexical vote if it is rare enough across the
            // pool to tell candidates apart. Without that, "POSH Policy" is
            // admitted everywhere by "policy" alone.
            List<String> queryTokens = LexicalMatcher.discriminative(
                LexicalMatcher.queryTokens(query, caseSensitive), poolTexts, caseSensitive, maxDocFreqRatio);
            List<EmbeddingMatch<TextSegment>> candidates = new ArrayList<>();
            List<String> candidateTexts = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                boolean lexical = LexicalMatcher.covers(
                    poolTexts.get(i), queryTokens, caseSensitive, lexicalCoverage);
                if (!lexical && pool.get(i).score() < minScore) continue;
                candidates.add(pool.get(i));
                candidateTexts.add(poolTexts.get(i));
            }

            // Stage 2 — reorder the candidate pool: hybrid (RRF) then optional
            // cross-encoder rerank. Both are no-ops that preserve dense order when
            // not enabled / not configured. BM25 runs over the SEARCHABLE text so a
            // query naming the document ("POSH", "Social Media Policy") ranks that
            // document's chunks even when the wording appears only in its title.
            List<EmbeddingMatch<TextSegment>> ordered = candidates;
            if (hybrid && candidates.size() > 1) {
                ordered = reorder(candidates, HybridScorer.fuse(candidateTexts, query, caseSensitive));
            }
            // Cut to topK now that the pool is correctly ordered, so the (costly)
            // cross-encoder only sees the passages that can still make the answer.
            if (ordered.size() > topK) ordered = new ArrayList<>(ordered.subList(0, topK));
            if (rerank && ordered.size() > 1) {
                List<String> ot = new ArrayList<>(ordered.size());
                for (EmbeddingMatch<TextSegment> m : ordered) ot.add(m.embedded().text());
                ordered = reorder(ordered, rerankClient.rerankOrder(query, ot));
            }

            // Stage 3 — take the top maxCitations and build the response.
            List<Map<String, Object>> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : ordered) {
                results.add(buildResult(m, m.embedded()));
                if (results.size() >= maxCitations) break;
            }

            long latencyMs = System.currentTimeMillis() - start;
            queryHistory.record(tenantId, userSub, query, results.size(), latencyMs);

            // Pool and candidate counts are the difference between "the corpus has
            // nothing for this query" and "the relevance gate rejected everything",
            // which is not something the caller can tell from an empty result list.
            log.debug("Doc search: query='{}' tenant={} mode={} rerank={} match={} "
                    + "pool={} candidates={} terms={} results={} latency={}ms",
                query, tenantId, hybrid ? "hybrid" : "dense", rerank, matching,
                pool.size(), candidates.size(), queryTokens, results.size(), latencyMs);
            if (results.isEmpty()) {
                log.info("Doc search returned nothing: query='{}' tenant={} — {} chunk(s) cleared the "
                        + "recall floor, {} passed the relevance gate (min-score={}, terms={})",
                    query, tenantId, pool.size(), candidates.size(), minScore, queryTokens);
            }

            return ResponseEntity.ok(Map.of(
                "results",  results,
                "query",    query,
                "total",    results.size(),
                "mode",     hybrid ? "hybrid" : "dense",
                "reranked", rerank && rerankClient.isConfigured(),
                "language", s.answerLanguage(),
                "model",    s.model()
            ));

        } catch (Exception e) {
            log.error("Doc search failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }

    // ── Filter + matching helpers ───────────────────────────────────────────────

    /**
     * Shared corpus (no {@code owner_sub}) is visible tenant-wide; a private capture
     * only to the user it belongs to.
     */
    static boolean visibleTo(TextSegment seg, String callerSub) {
        String owner = seg.metadata().getString("owner_sub");
        return owner == null || owner.isBlank() || owner.equals(callerSub);
    }

    private static boolean matchesDocType(TextSegment seg, String filter) {
        if (filter == null || filter.isBlank() || "Any".equalsIgnoreCase(filter)) return true;
        String dt = seg.metadata().getString("document_type");
        return dt != null && dt.equalsIgnoreCase(filter);
    }

    /** Keeps chunks whose effective_date is on/after the filter (ISO yyyy-MM-dd string compare). */
    private static boolean matchesEffectiveAfter(TextSegment seg, String after) {
        if (after == null || after.isBlank()) return true;
        String ed = seg.metadata().getString("effective_date");
        if (ed == null || ed.isBlank()) return false;   // no date → can't satisfy an "after" filter
        // ISO yyyy-MM-dd sorts lexicographically; tolerate longer timestamps by prefix.
        String edDay = ed.length() >= 10 ? ed.substring(0, 10) : ed;
        return edDay.compareTo(after) >= 0;
    }

    private static boolean matchesPassage(String text, String query, String matching,
                                          boolean caseSensitive, boolean ignoreWs) {
        if (matching == null || matching.isBlank() || "SEMANTIC".equalsIgnoreCase(matching)) return true;
        if (text == null) return false;
        String hay = normalize(text, caseSensitive, ignoreWs);
        if ("EXACT_PHRASE".equalsIgnoreCase(matching)) {
            String needle = normalize(query, caseSensitive, ignoreWs);
            return hay.contains(needle);
        }
        if ("ALL_KEYWORDS".equalsIgnoreCase(matching)) {
            // Bilingual tokenization: Japanese has no spaces, so a whitespace split
            // would treat the whole query as one keyword. Kuromoji segments it into
            // real keywords (and still handles English). hay is already normalized.
            for (String tok : JapaneseTextTokenizer.tokenize(query, caseSensitive)) {
                if (!tok.isBlank() && !hay.contains(tok)) return false;
            }
            return true;
        }
        return true;
    }

    private static String normalize(String s, boolean caseSensitive, boolean ignoreWs) {
        String out = caseSensitive ? s : s.toLowerCase();
        if (ignoreWs) out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    private static <T> List<T> reorder(List<T> items, List<Integer> order) {
        List<T> out = new ArrayList<>(items.size());
        for (int idx : order) if (idx >= 0 && idx < items.size()) out.add(items.get(idx));
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int firstNonNull(Integer v, int fallback)   { return v != null ? v : fallback; }
    private static boolean firstNonNull(Boolean v, boolean fb) { return v != null ? v : fb; }
    private static String firstNonBlank(String v, String fb)   { return (v != null && !v.isBlank()) ? v : fb; }

    /**
     * Human-readable identity for history/audit display: preferred_username,
     * then email, then the opaque sub as a last resort.
     */
    static String displayName(Jwt jwt) {
        if (jwt == null) return "";
        for (String claim : new String[]{"preferred_username", "email"}) {
            String v = jwt.getClaimAsString(claim);
            if (v != null && !v.isBlank()) return v;
        }
        String sub = jwt.getSubject();
        return sub == null ? "" : sub;
    }

    // ── The caller's own query history ────────────────────────────────────────

    /**
     * The signed-in user's own document searches, newest first — the source for
     * the AI Co-pilot history panel's "Document search" tab.
     *
     * No @PreAuthorize: every authenticated user may read their OWN history.
     * The scoping is in the query (tenant + subject), not in a role check, which
     * is what keeps this separate from the admin endpoint below — that one is
     * tenant-wide and would expose colleagues' searches.
     */
    @GetMapping("/me/search/history")
    public List<Map<String, Object>> mySearchHistory(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return queryHistory.listForUser(currentTenantId(), displayName(jwt), clamp(limit, 1, 200))
            .stream()
            .map(e -> Map.<String, Object>of(
                "searchId",    e.searchId(),
                "query",       e.query(),
                "resultCount", e.resultCount(),
                "latencyMs",   e.latencyMs(),
                "timestamp",   e.timestamp().toString()
            ))
            .toList();
    }

    /** Delete one of the caller's own history entries. */
    @DeleteMapping("/me/search/history/{searchId}")
    public ResponseEntity<Void> deleteMySearch(@PathVariable String searchId,
                                               @AuthenticationPrincipal Jwt jwt) {
        queryHistory.deleteForUser(currentTenantId(), displayName(jwt), searchId);
        return ResponseEntity.noContent().build();
    }

    /** Clear the caller's own history. */
    @DeleteMapping("/me/search/history")
    public ResponseEntity<Void> clearMySearches(@AuthenticationPrincipal Jwt jwt) {
        queryHistory.clearForUser(currentTenantId(), displayName(jwt));
        return ResponseEntity.noContent().build();
    }

    // ── Admin: query history ──────────────────────────────────────────────────

    @GetMapping("/admin/search/history")
    @PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
    public List<Map<String, Object>> searchHistory(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        String tenantId = currentTenantId();
        return queryHistory.list(tenantId, limit).stream()
            .map(e -> Map.<String, Object>of(
                "searchId",    e.searchId(),
                "tenantId",    e.tenantId(),
                "userSub",     e.userSub(),
                "query",       e.query(),
                "resultCount", e.resultCount(),
                "latencyMs",   e.latencyMs(),
                "timestamp",   e.timestamp().toString()
            ))
            .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildResult(EmbeddingMatch<TextSegment> match, TextSegment seg) {
        var meta = seg.metadata();
        String docId = getString(meta, "document_id");

        // Enrich from DocumentRegistry so we surface any title set at ingest time
        DocumentRegistry.Entry entry = docId != null ? documentRegistry.get(docId) : null;
        String title = coalesce(
            entry != null ? entry.getTitle() : null,
            getString(meta, "title"),
            getString(meta, "file_name"),
            docId != null ? docId.substring(0, Math.min(8, docId.length())) + "…" : "unknown"
        );

        int pageNumber  = parseInt(getString(meta, "page_number"),  0);
        int chunkIndex  = parseInt(getString(meta, "chunk_index"),  0);

        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("documentId",      coalesce(docId, ""));
        r.put("title",           title);
        r.put("fileName",        coalesce(getString(meta, "file_name"), ""));
        r.put("documentType",    coalesce(getString(meta, "document_type"), ""));
        r.put("referenceNumber", coalesce(getString(meta, "reference_number"), ""));
        r.put("category",        coalesce(getString(meta, "category"), ""));
        r.put("department",      coalesce(getString(meta, "department"), ""));
        r.put("folder",          coalesce(getString(meta, "folder"), ""));
        r.put("text",            seg.text());
        r.put("score",           Math.round(match.score() * 1000.0) / 1000.0);
        r.put("pageNumber",      pageNumber);
        r.put("chunkIndex",      chunkIndex);
        return r;
    }

    /**
     * Resolves the acting tenant from {@link TenantContextHolder}, which is
     * populated by the common {@code TenantResolutionFilter}. This honours both
     * the per-tenant JWT {@code tenant_id} claim AND a platform_admin's
     * {@code X-Tenant-Id} acting-tenant header — the latter is how the admin
     * tabs (Ingested Documents, Doc Search History) reach this endpoint, since a
     * platform_admin's JWT carries no {@code tenant_id} claim.
     */
    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String getString(dev.langchain4j.data.document.Metadata meta, String key) {
        try { return meta.getString(key); } catch (Exception e) { return null; }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    @SafeVarargs
    private static <T> T coalesce(T... vals) {
        for (T v : vals) if (v != null && !v.toString().isBlank()) return v;
        return vals[vals.length - 1];
    }
}
