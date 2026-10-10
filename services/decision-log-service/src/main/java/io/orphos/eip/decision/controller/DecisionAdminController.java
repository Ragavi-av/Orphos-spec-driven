package io.orphos.eip.decision.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.repository.DecisionRepository;
import io.orphos.eip.decision.service.DecisionService;
import io.orphos.eip.decision.service.RagSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operational endpoints: on-demand RAG sync, bulk legacy-schema migration
 * (Req 9.2), and tenant-defined correlation rules (Req 11.4). The shell only
 * surfaces these to admins, and server-side they require the tenant {@code admin}
 * role (platform admins inherit it) or an internal-service caller.
 */
@RestController
@RequestMapping("/api/decisions/admin")
@PreAuthorize("hasRole('admin') or hasRole('service_internal')")
public class DecisionAdminController {

    private final RagSyncService sync;
    private final DecisionService decisions;
    private final DecisionRepository repo;

    public DecisionAdminController(RagSyncService sync, DecisionService decisions, DecisionRepository repo) {
        this.sync = sync;
        this.decisions = decisions;
        this.repo = repo;
    }

    /** Runs one RAG sweep for the caller's tenant right now (instead of waiting for the 5-min tick). */
    @PostMapping("/sync")
    public ResponseEntity<Object> syncNow() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(sync.syncTenant(tenantId));
    }

    /**
     * Bulk migration of legacy decisions into the RAG schema (Req 9.2):
     * loops migrateBatch until the tenant has no unmigrated rows left.
     */
    @PostMapping("/migrate")
    public ResponseEntity<Object> migrate(@RequestParam(defaultValue = "1000") int max) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        int total = 0;
        int batch;
        do {
            batch = decisions.migrateBatch(tenantId, 100);
            total += batch;
        } while (batch > 0 && total < Math.min(max, 10_000));
        return ResponseEntity.ok(Map.of("migrated", total));
    }

    @GetMapping("/correlation-rules")
    public ResponseEntity<Object> rules() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        return ResponseEntity.ok(repo.correlationRules(tenantId, false));
    }

    /** Body: {@code {name, targetType, keywordPattern, relation?, confidence?, enabled?}} */
    @PostMapping("/correlation-rules")
    public ResponseEntity<Object> createRule(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        String name = String.valueOf(body.getOrDefault("name", "")).strip();
        String pattern = String.valueOf(body.getOrDefault("keywordPattern", "")).strip();
        if (name.isBlank() || pattern.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name and keywordPattern are required"));
        }
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid keywordPattern: " + e.getDescription()));
        }
        return ResponseEntity.status(201).body(repo.insertCorrelationRule(tenantId, body));
    }

    @DeleteMapping("/correlation-rules/{ruleId}")
    public ResponseEntity<Object> deleteRule(@PathVariable String ruleId) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        repo.deleteCorrelationRule(tenantId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
