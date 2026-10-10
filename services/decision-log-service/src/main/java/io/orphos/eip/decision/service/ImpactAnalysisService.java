package io.orphos.eip.decision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.core.ai.NlQueryInternalClient;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.util.LlmJson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Impact_Analysis (Req 4): predicts a decision's consequences on departments,
 * people and policies using the tenant's LLM, grounded in the most similar
 * historical decisions retrieved from the vector store — including their
 * recorded actual outcomes, which is how prediction quality improves as
 * outcomes accumulate (Req 4.2/4.5 continuous learning).
 *
 * <p>Degrades to a deterministic heuristic (priority + affected-area counts)
 * when no model is reachable, so the API contract never breaks.</p>
 */
@Service
public class ImpactAnalysisService {

    private static final Logger log = LogManager.getLogger(ImpactAnalysisService.class);

    static final String USAGE_TYPE = "decision-impact";
    private static final int SIMILAR_COUNT = 5;

    private final DecisionRepository repo;
    private final DecisionEmbeddingService embeddings;
    private final NlQueryInternalClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImpactAnalysisService(DecisionRepository repo, DecisionEmbeddingService embeddings,
                                 NlQueryInternalClient llm) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.llm = llm;
    }

    /**
     * Runs (or re-runs) impact prediction for a decision, persists the result
     * to {@code predictedImpactJson}/{@code impactSummary}, and returns it.
     */
    public Map<String, Object> predict(String tenantId, String decisionId) {
        Map<String, Object> decision = repo.findById(tenantId, decisionId);
        if (decision == null) return null;

        List<DecisionEmbeddingService.SemanticMatch> similar =
            embeddings.findSimilar(tenantId, decision, SIMILAR_COUNT);
        List<Map<String, Object>> similarRows = repo.findByIds(tenantId,
            similar.stream().map(DecisionEmbeddingService.SemanticMatch::decisionId).toList());

        ObjectNode prediction = llmPredict(tenantId, decision, similar, similarRows)
            .orElseGet(() -> heuristicPredict(decision, similar, similarRows));

        String json = prediction.toString();
        repo.update(tenantId, decisionId, Map.of(
            "predictedImpactJson", json,
            "impactSummary", prediction.path("summary").asText(""),
            "updatedAt", Instant.now().toString()));
        try {
            return mapper.convertValue(prediction, Map.class);
        } catch (Exception e) {
            return Map.of("summary", prediction.path("summary").asText(""));
        }
    }

    private Optional<ObjectNode> llmPredict(String tenantId, Map<String, Object> decision,
                                            List<DecisionEmbeddingService.SemanticMatch> similar,
                                            List<Map<String, Object>> similarRows) {
        if (!llm.isEnabled()) return Optional.empty();
        StringBuilder history = new StringBuilder();
        for (Map<String, Object> row : similarRows) {
            double score = similar.stream()
                .filter(m -> m.decisionId().equals(str(row.get("id"))))
                .mapToDouble(DecisionEmbeddingService.SemanticMatch::score).findFirst().orElse(0);
            history.append("- [similarity ").append(Math.round(score * 100)).append("%] \"")
                .append(str(row.get("title"))).append("\" (").append(str(row.get("domain"))).append(", ")
                .append(str(row.get("priorityLevel"))).append(")");
            String outcome = str(row.get("actualOutcome"));
            history.append(outcome.isBlank() ? " — outcome not yet recorded"
                : " — ACTUAL OUTCOME: " + truncate(outcome, 300));
            history.append('\n');
        }
        String prompt = """
            You are an organizational impact analyst. Predict the consequences of the DECISION
            below. Ground your prediction in the SIMILAR PAST DECISIONS and especially their
            actual outcomes where recorded. Respond with ONLY a JSON object:
            {
              "summary": "2-3 sentence executive impact summary",
              "overallRisk": "HIGH|MEDIUM|LOW",
              "confidence": 0.0-1.0,
              "impacts": [
                {"dimension": "PEOPLE|POLICY|OPERATIONS|FINANCIAL",
                 "area": "who/what is affected",
                 "severity": "HIGH|MEDIUM|LOW",
                 "likelihood": 0.0-1.0,
                 "description": "expected effect, quantified where possible"}
              ],
              "comparisons": [
                {"title": "similar decision title", "note": "what its outcome suggests here"}
              ]
            }

            DECISION:
            Title: %s
            Domain: %s | Area: %s | Priority: %s | Classification: %s
            Description: %s
            Rationale: %s
            Affected areas: %s

            SIMILAR PAST DECISIONS:
            %s
            """.formatted(
            str(decision.get("title")), str(decision.get("domain")), str(decision.get("decisionArea")),
            str(decision.get("priorityLevel")), str(decision.get("classificationType")),
            truncate(str(decision.get("description")), 1500), truncate(str(decision.get("rationale")), 800),
            str(decision.get("affectedAreasJson")),
            history.isEmpty() ? "(none found)" : history.toString());

        try {
            Optional<String> completion = llm.complete(prompt, UUID.fromString(tenantId), USAGE_TYPE);
            if (completion.isEmpty()) return Optional.empty();
            Optional<JsonNode> parsed = LlmJson.parse(completion.get());
            if (parsed.isEmpty() || !parsed.get().isObject()) return Optional.empty();
            ObjectNode node = (ObjectNode) parsed.get();
            node.put("method", "LLM");
            node.put("generatedAt", Instant.now().toString());
            attachComparisonIds(node, similarRows);
            return Optional.of(node);
        } catch (Exception e) {
            log.debug("LLM impact prediction failed, using heuristic: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Deterministic fallback so impact data always exists (quantitative floor, Req 1.5/4.4). */
    private ObjectNode heuristicPredict(Map<String, Object> decision,
                                        List<DecisionEmbeddingService.SemanticMatch> similar,
                                        List<Map<String, Object>> similarRows) {
        ObjectNode node = mapper.createObjectNode();
        String priority = str(decision.get("priorityLevel"));
        String risk = "CRITICAL".equals(priority) || "HIGH".equals(priority) ? "HIGH"
            : "MEDIUM".equals(priority) ? "MEDIUM" : "LOW";
        node.put("summary", "Heuristic assessment: " + priority + "-priority " + str(decision.get("domain"))
            + " decision; " + similarRows.size() + " comparable historical decision(s) found."
            + " Run with an AI provider configured for a grounded prediction.");
        node.put("overallRisk", risk);
        node.put("confidence", 0.3);
        node.put("method", "HEURISTIC");
        node.put("generatedAt", Instant.now().toString());
        ArrayNode impacts = node.putArray("impacts");
        try {
            JsonNode areas = mapper.readTree(str(decision.getOrDefault("affectedAreasJson", "[]")));
            for (JsonNode area : areas) {
                ObjectNode impact = impacts.addObject();
                String kind = area.path("kind").asText("DEPARTMENT");
                impact.put("dimension", "POLICY".equals(kind) ? "POLICY"
                    : "PERSON".equals(kind) ? "PEOPLE" : "OPERATIONS");
                impact.put("area", area.path("name").asText(""));
                impact.put("severity", risk);
                impact.put("likelihood", 0.5);
                impact.put("description", "Directly listed as an affected area on the decision.");
            }
        } catch (Exception ignored) { /* empty impacts list is valid */ }
        ArrayNode comparisons = node.putArray("comparisons");
        for (Map<String, Object> row : similarRows) {
            ObjectNode c = comparisons.addObject();
            c.put("decisionId", str(row.get("id")));
            c.put("title", str(row.get("title")));
            String outcome = str(row.get("actualOutcome"));
            c.put("note", outcome.isBlank() ? "No outcome recorded yet." : truncate(outcome, 200));
        }
        return node;
    }

    /** Ensures every comparison entry carries the matching decisionId for UI navigation. */
    private void attachComparisonIds(ObjectNode node, List<Map<String, Object>> similarRows) {
        JsonNode comparisons = node.path("comparisons");
        if (!comparisons.isArray()) return;
        for (JsonNode c : comparisons) {
            if (!(c instanceof ObjectNode obj) || obj.hasNonNull("decisionId")) continue;
            String title = obj.path("title").asText("");
            similarRows.stream()
                .filter(r -> str(r.get("title")).equalsIgnoreCase(title))
                .findFirst()
                .ifPresent(r -> obj.put("decisionId", str(r.get("id"))));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
