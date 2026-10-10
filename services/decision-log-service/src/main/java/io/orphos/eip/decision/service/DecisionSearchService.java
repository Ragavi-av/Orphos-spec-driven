package io.orphos.eip.decision.service;

import io.orphos.eip.decision.util.DecisionLogProperties;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.repository.DecisionRepository.DecisionFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decision_Search_Engine (Req 6): full-text keyword search, RAG semantic
 * search, and a reciprocal-rank-fusion hybrid of both, with faceted filters
 * and highlightable snippets. Facet counts are computed over the filtered
 * result set so the UI can narrow progressively.
 */
@Service
public class DecisionSearchService {

    private static final int RRF_K = 60;   // standard reciprocal-rank-fusion constant
    private static final int SNIPPET_RADIUS = 90;

    private final DecisionRepository repo;
    private final DecisionEmbeddingService embeddings;
    private final DecisionLogProperties props;

    public DecisionSearchService(DecisionRepository repo, DecisionEmbeddingService embeddings,
                                 DecisionLogProperties props) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.props = props;
    }

    public record SearchRequest(String query, String mode, DecisionFilter filter, int limit) {}

    /**
     * @return {results: [{decision..., relevance, snippet, matchedField}], facets: {...}, total, mode}
     */
    public Map<String, Object> search(String tenantId, SearchRequest req) {
        int limit = req.limit() > 0 ? Math.min(req.limit(), 200) : props.getSearch().getMaxResults();
        String mode = req.mode() == null ? "HYBRID" : req.mode().toUpperCase(Locale.ROOT);
        boolean hasQuery = req.query() != null && !req.query().isBlank();
        if (!hasQuery) mode = "KEYWORD";   // facet-only browse

        List<Map<String, Object>> keywordHits = keywordSearch(tenantId, req, limit * 2);
        List<Map<String, Object>> results;
        switch (mode) {
            case "SEMANTIC" -> results = semanticSearch(tenantId, req, limit);
            case "KEYWORD" -> results = rank(keywordHits, limit);
            default -> {   // HYBRID
                results = fuse(keywordHits, semanticSearch(tenantId, req, limit * 2), limit);
                mode = "HYBRID";
            }
        }
        if (hasQuery) {
            for (Map<String, Object> r : results) {
                addSnippet(r, req.query());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("facets", facets(keywordHits.isEmpty() && !"KEYWORD".equals(mode)
            ? results : keywordHits));
        out.put("total", results.size());
        out.put("mode", mode);
        return out;
    }

    private List<Map<String, Object>> keywordSearch(String tenantId, SearchRequest req, int limit) {
        DecisionFilter base = req.filter() == null ? DecisionFilter.none() : req.filter();
        DecisionFilter withQuery = new DecisionFilter(req.query(), base.statuses(), base.domains(),
            base.areas(), base.priorities(), base.detectionSources(),
            base.dateFrom(), base.dateTo(), base.person(), base.impactArea());
        return repo.list(tenantId, withQuery, limit, 0);
    }

    private List<Map<String, Object>> semanticSearch(String tenantId, SearchRequest req, int limit) {
        List<DecisionEmbeddingService.SemanticMatch> matches =
            embeddings.search(tenantId, req.query(), limit);
        if (matches.isEmpty()) return List.of();
        List<Map<String, Object>> rows = repo.findByIds(tenantId,
            matches.stream().map(DecisionEmbeddingService.SemanticMatch::decisionId).toList());
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) byId.put(str(row.get("id")), row);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DecisionEmbeddingService.SemanticMatch m : matches) {
            Map<String, Object> row = byId.get(m.decisionId());
            if (row == null || !passesFilter(row, req.filter())) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("relevance", round3(m.score()));
            out.add(copy);
        }
        return out;
    }

    /** Reciprocal-rank fusion of the keyword and semantic rankings (Req 6.3 hybrid relevance). */
    private static List<Map<String, Object>> fuse(List<Map<String, Object>> keyword,
                                                  List<Map<String, Object>> semantic, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rows = new HashMap<>();
        accumulate(scores, rows, keyword);
        accumulate(scores, rows, semantic);
        return scores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> {
                Map<String, Object> copy = new LinkedHashMap<>(rows.get(e.getKey()));
                copy.put("relevance", round3(e.getValue()));
                return copy;
            })
            .toList();
    }

    private static void accumulate(Map<String, Double> scores, Map<String, Map<String, Object>> rows,
                                   List<Map<String, Object>> ranking) {
        for (int i = 0; i < ranking.size(); i++) {
            Map<String, Object> row = ranking.get(i);
            String id = str(row.get("id"));
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            rows.putIfAbsent(id, row);
        }
    }

    private static List<Map<String, Object>> rank(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            Map<String, Object> copy = new LinkedHashMap<>(rows.get(i));
            copy.put("relevance", round3(1.0 - (double) i / Math.max(rows.size(), 1)));
            out.add(copy);
        }
        return out;
    }

    /** Applies the faceted filter in-memory to semantic hits (keyword hits are filtered in SQL). */
    private static boolean passesFilter(Map<String, Object> row, DecisionFilter f) {
        if (f == null) return true;
        if (missesIn(row, "status", f.statuses())) return false;
        if (missesIn(row, "domain", f.domains())) return false;
        if (missesIn(row, "decisionArea", f.areas())) return false;
        if (missesIn(row, "priorityLevel", f.priorities())) return false;
        if (missesIn(row, "detectionSource", f.detectionSources())) return false;
        String decidedAt = str(row.get("decidedAt"));
        if (f.dateFrom() != null && !f.dateFrom().isBlank() && decidedAt.compareTo(f.dateFrom()) < 0) return false;
        if (f.dateTo() != null && !f.dateTo().isBlank() && decidedAt.compareTo(f.dateTo()) > 0) return false;
        if (f.person() != null && !f.person().isBlank()) {
            String hay = (str(row.get("decidedBy")) + " " + str(row.get("involvedPeopleJson"))).toLowerCase(Locale.ROOT);
            if (!hay.contains(f.person().toLowerCase(Locale.ROOT))) return false;
        }
        if (f.impactArea() != null && !f.impactArea().isBlank()) {
            String hay = str(row.get("affectedAreasJson")).toLowerCase(Locale.ROOT);
            if (!hay.contains(f.impactArea().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static boolean missesIn(Map<String, Object> row, String field, List<String> allowed) {
        return allowed != null && !allowed.isEmpty() && !allowed.contains(str(row.get(field)));
    }

    /** Facet counts by domain / priority / status / area / source (Req 6.2). */
    private static Map<String, Object> facets(List<Map<String, Object>> rows) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("domain", countBy(rows, "domain"));
        facets.put("decisionArea", countBy(rows, "decisionArea"));
        facets.put("priorityLevel", countBy(rows, "priorityLevel"));
        facets.put("status", countBy(rows, "status"));
        facets.put("detectionSource", countBy(rows, "detectionSource"));
        return facets;
    }

    private static Map<String, Integer> countBy(List<Map<String, Object>> rows, String field) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String v = str(row.get(field));
            if (!v.isBlank()) counts.merge(v, 1, Integer::sum);
        }
        return counts;
    }

    /** First matching field → contextual snippet the UI highlights the query terms in (Req 6.5). */
    private static void addSnippet(Map<String, Object> row, String query) {
        String q = query.toLowerCase(Locale.ROOT).strip();
        for (String field : List.of("title", "description", "rationale", "impactSummary", "decidedBy")) {
            String value = str(row.get(field));
            int idx = value.toLowerCase(Locale.ROOT).indexOf(q);
            if (idx < 0) continue;
            int from = Math.max(0, idx - SNIPPET_RADIUS);
            int to = Math.min(value.length(), idx + q.length() + SNIPPET_RADIUS);
            String snippet = (from > 0 ? "…" : "") + value.substring(from, to) + (to < value.length() ? "…" : "");
            row.put("snippet", snippet);
            row.put("matchedField", field);
            return;
        }
        // Semantic-only hit: no literal occurrence — use the description head as context.
        String fallback = str(row.get("description")).isBlank() ? str(row.get("rationale")) : str(row.get("description"));
        if (!fallback.isBlank()) {
            row.put("snippet", fallback.length() > 2 * SNIPPET_RADIUS
                ? fallback.substring(0, 2 * SNIPPET_RADIUS) + "…" : fallback);
            row.put("matchedField", "semantic");
        }
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
