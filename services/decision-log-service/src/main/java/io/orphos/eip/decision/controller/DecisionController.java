package io.orphos.eip.decision.controller;

import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.repository.DecisionRepository.DecisionFilter;
import io.orphos.eip.decision.service.CorrelationService;
import io.orphos.eip.decision.service.DecisionService;
import io.orphos.eip.decision.util.AuthSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Core decision CRUD + real-time tracking API.
 *
 * <p>Backward compatible with the pre-RAG contract (Req 9.1/9.3):
 * {@code GET /api/decisions} still returns a plain array of decisions and
 * {@code POST /api/decisions} still accepts the minimal
 * title/status/domain/rationale/decidedBy payload — both now enriched with
 * the RAG-schema fields and the legacy {@code timestamp} alias.</p>
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final DecisionService decisions;
    private final DecisionRepository repo;
    private final CorrelationService correlationService;
    private final AccessGrantSource grants;

    /**
     * Persona filter ({@link PersonaDomainPolicy}): a decision is shown to the personas
     * whose domains cover it — by its {@code domain} label, else by its title and rationale.
     * Critical ones also reach the CEO. Applied after paging, so a page may hold fewer rows.
     */
    private static boolean visible(PersonaDomainPolicy.Scope scope, Map<String, Object> d) {
        if (scope.unrestricted()) return true;
        String domain = DataDomains.of(str(d.get("domain")), str(d.get("title")), str(d.get("rationale")));
        boolean critical = "CRITICAL".equalsIgnoreCase(str(d.get("priorityLevel")))
                || "CRITICAL".equalsIgnoreCase(str(d.get("priority")));
        return scope.allows(domain, critical, false);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    public DecisionController(DecisionService decisions, DecisionRepository repo, AccessGrantSource grants,
                              CorrelationService correlationService) {
        this.decisions = decisions;
        this.repo = repo;
        this.correlationService = correlationService;
        this.grants = grants;
    }

    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> domain,
            @RequestParam(required = false) List<String> area,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) List<String> detectionSource,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String person,
            @RequestParam(required = false) String impactArea,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        DecisionFilter filter = new DecisionFilter(q, status, domain, area, priority, detectionSource,
            dateFrom, dateTo, person, impactArea);
        List<Map<String, Object>> rows = repo.list(tenantId, filter, limit, offset);
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        return ResponseEntity.ok(rows.stream().filter(r -> visible(scope, r)).map(decisions::expand).toList());
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> payload, Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.status(201)
            .body(decisions.create(tenantId, payload, AuthSupport.actor(auth)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable String id) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> decision = decisions.get(tenantId, id);
        return decision == null || !visible(PersonaDomainPolicy.current(grants), decision)
                ? ResponseEntity.notFound().build() : ResponseEntity.ok(decision);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Map<String, Object> payload,
                                         Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> updated = decisions.update(tenantId, id, payload, AuthSupport.actor(auth));
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /**
     * Status / implementation transition (Req 10.1). Body:
     * {@code {status?, implementationStatus?, note?, source?}} — external
     * project-management sync passes {@code source: "EXTERNAL"} (Req 10.4).
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Object> changeStatus(@PathVariable String id,
                                               @RequestBody Map<String, Object> body, Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        String source = String.valueOf(body.getOrDefault("source", "UI"));
        Map<String, Object> updated = decisions.changeStatus(tenantId, id,
            (String) body.get("status"), (String) body.get("implementationStatus"),
            (String) body.get("note"), AuthSupport.actor(auth),
            "EXTERNAL".equalsIgnoreCase(source) ? "EXTERNAL" : "UI");
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** Milestone list replacement (Req 10.3). Body: {@code {milestones: [{name,dueDate,status}]}} */
    @PatchMapping("/{id}/milestones")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> updateMilestones(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body, Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Object milestones = body.get("milestones");
        Map<String, Object> updated = decisions.updateMilestones(tenantId, id,
            milestones instanceof List ? (List<Map<String, Object>>) milestones : List.of(),
            AuthSupport.actor(auth));
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** Records the actual outcome for continuous prediction learning (Req 4.5). Body: {@code {outcome}} */
    @PostMapping("/{id}/outcome")
    public ResponseEntity<Object> recordOutcome(@PathVariable String id,
                                                @RequestBody Map<String, Object> body, Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> updated = decisions.recordOutcome(tenantId, id,
            (String) body.get("outcome"), AuthSupport.actor(auth));
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** Chronological status/implementation/milestone/escalation audit trail (Req 5.3, 10.3). */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<Object> timeline(@PathVariable String id) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(decisions.timeline(tenantId, id));
    }

    /** Stored cross-system correlations for a decision (Req 11). */
    @GetMapping("/{id}/correlations")
    public ResponseEntity<Object> correlations(@PathVariable String id) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(correlationService.list(tenantId, id));
    }

    /** Decision-area taxonomy (hierarchical via parentId, Req 1.1). */
    @GetMapping("/areas")
    public ResponseEntity<Object> areas() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(repo.areas(tenantId));
    }

    @PostMapping("/areas")
    public ResponseEntity<Object> createArea(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        String name = String.valueOf(body.getOrDefault("name", "")).strip();
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        return ResponseEntity.status(201).body(repo.insertArea(tenantId, name,
            (String) body.get("parentId"), (String) body.get("description")));
    }
}
