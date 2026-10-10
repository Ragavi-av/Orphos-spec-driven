package io.orphos.eip.decision.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.service.AiAnnotationService;
import io.orphos.eip.decision.service.DecisionEmbeddingService;
import io.orphos.eip.decision.service.ImpactAnalysisService;
import io.orphos.eip.decision.util.AuthSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-copilot surface for one decision: role-adapted annotation (Req 7),
 * ML impact prediction (Req 4), and semantically related decisions
 * (Req 5.2 / 7.3).
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionAiController {

    private final AiAnnotationService annotations;
    private final ImpactAnalysisService impacts;
    private final DecisionEmbeddingService embeddings;
    private final DecisionRepository repo;

    public DecisionAiController(AiAnnotationService annotations, ImpactAnalysisService impacts,
                                DecisionEmbeddingService embeddings, DecisionRepository repo) {
        this.annotations = annotations;
        this.impacts = impacts;
        this.embeddings = embeddings;
        this.repo = repo;
    }

    /** Cached-by-default AI annotation, adapted to the caller's role or an explicit {@code role} override. */
    @GetMapping("/{id}/annotation")
    public ResponseEntity<Object> annotation(@PathVariable String id,
                                             @RequestParam(required = false) String role,
                                             @RequestParam(defaultValue = "false") boolean refresh,
                                             Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        String audienceRole = role != null && !role.isBlank() ? role : AuthSupport.audienceRole(auth);
        Map<String, Object> annotation = annotations.annotate(tenantId, id, audienceRole, refresh);
        return annotation == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(annotation);
    }

    /** Runs (or re-runs) impact prediction and returns the stored result (Req 4.1-4.4). */
    @PostMapping("/{id}/impact")
    public ResponseEntity<Object> predictImpact(@PathVariable String id) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> prediction = impacts.predict(tenantId, id);
        return prediction == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(prediction);
    }

    /** Semantically similar decisions with similarity scores (Req 7.3, timeline "related" panel Req 5.2). */
    @GetMapping("/{id}/related")
    public ResponseEntity<Object> related(@PathVariable String id,
                                          @RequestParam(defaultValue = "5") int limit) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> decision = repo.findById(tenantId, id);
        if (decision == null) return ResponseEntity.notFound().build();
        List<DecisionEmbeddingService.SemanticMatch> matches =
            embeddings.findSimilar(tenantId, decision, Math.min(Math.max(limit, 1), 20));
        List<Map<String, Object>> rows = repo.findByIds(tenantId,
            matches.stream().map(DecisionEmbeddingService.SemanticMatch::decisionId).toList());
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) byId.put(String.valueOf(row.get("id")), row);
        List<Map<String, Object>> out = matches.stream()
            .filter(m -> byId.containsKey(m.decisionId()))
            .map(m -> {
                Map<String, Object> row = new LinkedHashMap<>(byId.get(m.decisionId()));
                row.put("similarity", Math.round(m.score() * 1000.0) / 1000.0);
                return row;
            })
            .toList();
        return ResponseEntity.ok(out);
    }
}
