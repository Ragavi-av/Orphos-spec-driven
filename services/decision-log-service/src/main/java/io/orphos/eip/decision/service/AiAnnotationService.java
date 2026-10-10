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
 * AI_Copilot decision annotation (Req 7): generates an executive summary,
 * key impacts, recommendations and suggested related decisions for one
 * decision, adapted to the viewer's role (Req 7.5). Results are cached per
 * (decision, role) in {@code DecisionAnnotation} and regenerated on demand
 * ({@code refresh=true}) or implicitly when the decision was updated after
 * the cached annotation was created.
 */
@Service
public class AiAnnotationService {

    private static final Logger log = LogManager.getLogger(AiAnnotationService.class);

    static final String USAGE_TYPE = "decision-annotation";

    private final DecisionRepository repo;
    private final DecisionEmbeddingService embeddings;
    private final NlQueryInternalClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiAnnotationService(DecisionRepository repo, DecisionEmbeddingService embeddings,
                               NlQueryInternalClient llm) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.llm = llm;
    }

    public Map<String, Object> annotate(String tenantId, String decisionId, String audienceRole, boolean refresh) {
        Map<String, Object> decision = repo.findById(tenantId, decisionId);
        if (decision == null) return null;
        String role = normalizeRole(audienceRole);

        if (!refresh) {
            Map<String, Object> cached = repo.annotation(tenantId, decisionId, role);
            if (cached != null && !isStale(cached, decision)) {
                Map<String, Object> parsed = parse(str(cached.get("annotationJson")));
                if (parsed != null) {
                    parsed.put("cached", true);
                    return parsed;
                }
            }
        }

        List<DecisionEmbeddingService.SemanticMatch> related = embeddings.findSimilar(tenantId, decision, 5);
        List<Map<String, Object>> relatedRows = repo.findByIds(tenantId,
            related.stream().map(DecisionEmbeddingService.SemanticMatch::decisionId).toList());
        List<Map<String, Object>> correlations = repo.correlations(tenantId, decisionId);

        ObjectNode annotation = llmAnnotate(tenantId, decision, relatedRows, correlations, role)
            .orElseGet(() -> templateAnnotate(decision, relatedRows, correlations));
        annotation.put("audienceRole", role);
        annotation.put("generatedAt", Instant.now().toString());

        repo.saveAnnotation(tenantId, decisionId, role, annotation.toString(),
            annotation.path("method").asText("LLM"));
        Map<String, Object> out = parse(annotation.toString());
        out.put("cached", false);
        return out;
    }

    private Optional<ObjectNode> llmAnnotate(String tenantId, Map<String, Object> decision,
                                             List<Map<String, Object>> relatedRows,
                                             List<Map<String, Object>> correlations, String role) {
        if (!llm.isEnabled()) return Optional.empty();
        String audience = switch (role) {
            case "executive" -> "a C-level executive: lead with business impact and risk, max 3 bullets each, no technical detail";
            case "admin" -> "a platform administrator: include operational and compliance angles";
            default -> "a team member: practical, concrete, plain language";
        };
        StringBuilder relatedText = new StringBuilder();
        for (Map<String, Object> r : relatedRows) {
            relatedText.append("- ").append(str(r.get("title"))).append(" [").append(str(r.get("status")))
                .append(", ").append(str(r.get("decidedAt"))).append("]\n");
        }
        StringBuilder corrText = new StringBuilder();
        for (Map<String, Object> c : correlations) {
            corrText.append("- ").append(str(c.get("targetType"))).append(": ")
                .append(str(c.get("targetTitle"))).append(" (confidence ")
                .append(str(c.get("confidence"))).append(")\n");
        }
        String prompt = """
            You are the AI copilot of an enterprise decision log. Annotate the DECISION below
            for %s. Respond with ONLY a JSON object:
            {
              "executiveSummary": "...",
              "keyImpacts": ["..."],
              "recommendations": ["..."],
              "relatedConsiderations": ["..."],
              "suggestedConnections": ["titles of related decisions worth reviewing together"]
            }
            Answer in the language the decision is written in (English or Japanese).

            DECISION:
            Title: %s
            Status: %s | Implementation: %s | Priority: %s | Domain: %s | Area: %s
            Decided by: %s at %s
            Description: %s
            Rationale: %s
            Predicted impact summary: %s
            Actual outcome (if recorded): %s

            RELATED DECISIONS:
            %s
            CROSS-SYSTEM LINKS:
            %s
            """.formatted(audience,
            str(decision.get("title")), str(decision.get("status")),
            str(decision.get("implementationStatus")), str(decision.get("priorityLevel")),
            str(decision.get("domain")), str(decision.get("decisionArea")),
            str(decision.get("decidedBy")), str(decision.get("decidedAt")),
            truncate(str(decision.get("description")), 1500), truncate(str(decision.get("rationale")), 800),
            truncate(str(decision.get("impactSummary")), 500), truncate(str(decision.get("actualOutcome")), 500),
            relatedText.isEmpty() ? "(none)" : relatedText.toString(),
            corrText.isEmpty() ? "(none)" : corrText.toString());

        try {
            Optional<String> completion = llm.complete(prompt, UUID.fromString(tenantId), USAGE_TYPE);
            if (completion.isEmpty()) return Optional.empty();
            Optional<JsonNode> parsed = LlmJson.parse(completion.get());
            if (parsed.isEmpty() || !parsed.get().isObject()) return Optional.empty();
            ObjectNode node = (ObjectNode) parsed.get();
            node.put("method", "LLM");
            attachRelated(node, relatedRows);
            return Optional.of(node);
        } catch (Exception e) {
            log.debug("LLM annotation failed, using template: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Field-driven fallback annotation — factual, no model required. */
    private ObjectNode templateAnnotate(Map<String, Object> decision, List<Map<String, Object>> relatedRows,
                                        List<Map<String, Object>> correlations) {
        ObjectNode node = mapper.createObjectNode();
        node.put("executiveSummary", str(decision.get("title")) + " — " + str(decision.get("status"))
            + " " + str(decision.get("priorityLevel")) + "-priority " + str(decision.get("domain"))
            + " decision by " + str(decision.get("decidedBy")) + "."
            + (str(decision.get("impactSummary")).isBlank() ? "" : " " + str(decision.get("impactSummary"))));
        ArrayNode impacts = node.putArray("keyImpacts");
        for (Map<String, Object> c : correlations) {
            impacts.add(str(c.get("targetType")) + ": " + str(c.get("targetTitle")));
            if (impacts.size() >= 5) break;
        }
        ArrayNode recs = node.putArray("recommendations");
        if ("DETECTED".equals(str(decision.get("status")))) {
            recs.add("AI-detected decision — review and confirm or reject it.");
        }
        if (str(decision.get("actualOutcome")).isBlank()
                && "COMPLETED".equals(str(decision.get("implementationStatus")))) {
            recs.add("Implementation completed — record the actual outcome to improve future predictions.");
        }
        node.putArray("relatedConsiderations");
        ArrayNode connections = node.putArray("suggestedConnections");
        for (Map<String, Object> r : relatedRows) connections.add(str(r.get("title")));
        node.put("method", "TEMPLATE");
        attachRelated(node, relatedRows);
        return node;
    }

    private void attachRelated(ObjectNode node, List<Map<String, Object>> relatedRows) {
        ArrayNode arr = node.putArray("relatedDecisions");
        for (Map<String, Object> r : relatedRows) {
            ObjectNode o = arr.addObject();
            o.put("id", str(r.get("id")));
            o.put("title", str(r.get("title")));
            o.put("status", str(r.get("status")));
            o.put("decidedAt", str(r.get("decidedAt")));
        }
    }

    private boolean isStale(Map<String, Object> cached, Map<String, Object> decision) {
        String annotatedAt = str(cached.get("createdAt"));
        String updatedAt = str(decision.get("updatedAt"));
        return !annotatedAt.isBlank() && !updatedAt.isBlank() && updatedAt.compareTo(annotatedAt) > 0;
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeRole(String role) {
        if (role == null) return "user";
        String r = role.toLowerCase();
        return r.contains("admin") ? "admin" : r.contains("exec") || r.contains("cxo") ? "executive" : "user";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
