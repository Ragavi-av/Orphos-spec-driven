package io.orphos.eip.decision.service;

import io.orphos.eip.decision.repository.DecisionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Cross_System_Correlator (Req 11): maps decisions to entities elsewhere in
 * the shared ontology — Incidents, Alerts, BudgetItems (business metrics),
 * Projects, KeyResults, SLAMetrics — with a confidence score per link.
 *
 * <p>Two passes per decision: tenant-defined rules (regex on the decision
 * text → fixed-confidence links to a target type, Req 11.4) and a lexical
 * similarity pass (stopword-filtered token overlap between the decision text
 * and each candidate's title-ish fields). Correlations refresh on every
 * decision write and during the sync sweep (Req 11.5).</p>
 */
@Service
public class CorrelationService {

    private static final Logger log = LogManager.getLogger(CorrelationService.class);

    /** Ontology types decisions correlate against (whitelist — used in SQL FROM). */
    static final List<String> TARGET_TYPES =
        List.of("Incident", "Alert", "BudgetItem", "Project", "KeyResult", "SLAMetric");

    /** Fields (in priority order) that stand in for a candidate row's display text. */
    private static final List<String> TEXT_FIELDS =
        List.of("title", "name", "summary", "description", "metric", "id");

    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "to", "of", "in", "on", "for", "and", "or", "is", "are", "was", "were",
        "will", "we", "be", "with", "by", "at", "as", "it", "this", "that", "from", "has", "have");

    private static final double MIN_OVERLAP_CONFIDENCE = 0.25;
    private static final int MAX_LINKS_PER_TYPE = 5;

    private final DecisionRepository repo;

    public CorrelationService(DecisionRepository repo) {
        this.repo = repo;
    }

    /** Recomputes and stores automatic correlations for one decision. */
    public List<Map<String, Object>> refresh(String tenantId, Map<String, Object> decision) {
        String decisionId = str(decision.get("id"));
        String text = (str(decision.get("title")) + " " + str(decision.get("description")) + " "
            + str(decision.get("rationale"))).toLowerCase(Locale.ROOT);
        if (decisionId.isBlank() || text.isBlank()) return List.of();

        List<Map<String, Object>> found = new ArrayList<>();
        try {
            applyCustomRules(tenantId, text, found);
            applyLexicalPass(tenantId, text, found);
            // Legacy explicit links become first-class correlations too.
            addLegacyLink(found, decision, "linkedIncident", "Incident");
            addLegacyLink(found, decision, "linkedBudget", "BudgetItem");
            repo.replaceAutoCorrelations(tenantId, decisionId, dedupe(found));
        } catch (Exception e) {
            log.warn("Correlation refresh failed for decision {}: {}", decisionId, e.getMessage());
        }
        return found;
    }

    public List<Map<String, Object>> list(String tenantId, String decisionId) {
        return repo.correlations(tenantId, decisionId);
    }

    private void applyCustomRules(String tenantId, String decisionText, List<Map<String, Object>> out) {
        for (Map<String, Object> rule : repo.correlationRules(tenantId, true)) {
            String patternSrc = str(rule.get("keywordPattern"));
            String targetType = str(rule.get("targetType"));
            if (patternSrc.isBlank() || !TARGET_TYPES.contains(targetType)) continue;
            Pattern pattern;
            try {
                pattern = Pattern.compile(patternSrc, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                log.debug("Skipping correlation rule {} — bad pattern: {}", rule.get("id"), e.getMessage());
                continue;
            }
            if (!pattern.matcher(decisionText).find()) continue;
            for (Map<String, Object> candidate : repo.externalCandidates(tenantId, targetType, 100)) {
                String candidateText = displayText(candidate);
                if (candidateText.isBlank() || !pattern.matcher(candidateText.toLowerCase(Locale.ROOT)).find()) {
                    continue;
                }
                out.add(link(targetType, candidate, str(rule.get("relation")),
                    asDouble(rule.get("confidence"), 0.9), "RULE", str(rule.get("id"))));
            }
        }
    }

    private void applyLexicalPass(String tenantId, String decisionText, List<Map<String, Object>> out) {
        Set<String> decisionTokens = tokens(decisionText);
        if (decisionTokens.isEmpty()) return;
        for (String type : TARGET_TYPES) {
            List<Map<String, Object>> scored = new ArrayList<>();
            List<Map<String, Object>> candidates;
            try {
                candidates = repo.externalCandidates(tenantId, type, 100);
            } catch (Exception e) {
                continue;   // type absent in this deployment — fine
            }
            for (Map<String, Object> candidate : candidates) {
                double overlap = jaccard(decisionTokens, tokens(displayText(candidate)));
                if (overlap >= MIN_OVERLAP_CONFIDENCE) {
                    scored.add(link(type, candidate,
                        "BudgetItem".equals(type) || "SLAMetric".equals(type) ? "METRIC" : "RELATED",
                        Math.min(0.95, overlap), "AUTO", ""));
                }
            }
            scored.sort((a, b) -> Double.compare(asDouble(b.get("confidence"), 0), asDouble(a.get("confidence"), 0)));
            out.addAll(scored.subList(0, Math.min(scored.size(), MAX_LINKS_PER_TYPE)));
        }
    }

    private static void addLegacyLink(List<Map<String, Object>> out, Map<String, Object> decision,
                                      String field, String targetType) {
        String targetId = str(decision.get(field));
        if (!targetId.isBlank()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("targetType", targetType);
            link.put("targetId", targetId);
            link.put("targetTitle", targetId);
            link.put("relation", "IMPACTS");
            link.put("confidence", 1.0d);
            link.put("source", "AUTO");
            link.put("ruleId", "");
            out.add(link);
        }
    }

    private static Map<String, Object> link(String type, Map<String, Object> candidate, String relation,
                                            double confidence, String source, String ruleId) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("targetType", type);
        link.put("targetId", str(candidate.getOrDefault("id", candidate.get("@rid"))));
        link.put("targetTitle", displayText(candidate));
        link.put("relation", relation == null || relation.isBlank() ? "RELATED" : relation);
        link.put("confidence", Math.round(confidence * 1000.0) / 1000.0);
        link.put("source", source);
        link.put("ruleId", ruleId);
        return link;
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> links) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> link : links) {
            String key = link.get("targetType") + "|" + link.get("targetId");
            Map<String, Object> current = best.get(key);
            if (current == null || asDouble(link.get("confidence"), 0) > asDouble(current.get("confidence"), 0)) {
                best.put(key, link);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static String displayText(Map<String, Object> row) {
        for (String field : TEXT_FIELDS) {
            Object v = row.get(field);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) out.add(token);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) return 0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? fallback : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
