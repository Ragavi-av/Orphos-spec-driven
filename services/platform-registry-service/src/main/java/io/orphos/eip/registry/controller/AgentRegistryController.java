package io.orphos.eip.registry.controller;

import io.orphos.eip.agent.spec.AgentRegistration;
import io.orphos.eip.agent.spec.Autonomy;
import io.orphos.eip.registry.vo.AgentSummaryDto;
import io.orphos.eip.registry.service.AgentRegistry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Agent registry endpoints (TO-BE design §6). Mirrors {@code PluginRegistryController}:
 * self-registration, per-tenant gating, and tenant-scoped discovery of what's live.
 */
@RestController
@RequestMapping("/api/platform/agents")
public class AgentRegistryController {

    private final AgentRegistry service;

    public AgentRegistryController(AgentRegistry service) {
        this.service = service;
    }

    /** All registered agents — platform admin only (inventory + heartbeat status). */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public List<AgentSummaryDto> listAll() {
        return service.findAll();
    }

    /** Agents live for the calling tenant — consumed by the orchestrator. */
    @GetMapping("/active")
    public List<AgentSummaryDto> listActive(@AuthenticationPrincipal Jwt jwt,
                                            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        return service.findActiveForTenant(resolveTenant(jwt, tenantHeader));
    }

    /** Agent self-registration — called by each agent at startup. Internal service token required. */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public AgentSummaryDto register(@Valid @RequestBody AgentRegistration req) {
        return service.register(req);
    }

    /** Enable/disable an agent for a tenant — platform admin only. */
    @PutMapping("/{agentId}/tenants/{tenantId}/enabled")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Void> enable(@PathVariable String agentId,
                                       @PathVariable UUID tenantId,
                                       @RequestBody Map<String, Boolean> body,
                                       @AuthenticationPrincipal Jwt jwt) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        service.setEnabled(tenantId, agentId, enabled, jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.noContent().build();
    }

    /** Set the per-tenant autonomy override for an agent — platform admin only (design §12 Phase 4). */
    @PutMapping("/{agentId}/tenants/{tenantId}/autonomy")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Void> setAutonomy(@PathVariable String agentId,
                                            @PathVariable UUID tenantId,
                                            @RequestBody Map<String, String> body,
                                            @AuthenticationPrincipal Jwt jwt) {
        String raw = body.get("autonomy");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Body must contain 'autonomy'");
        }
        Autonomy autonomy;
        try {
            autonomy = Autonomy.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid autonomy: " + raw
                    + " (expected SUGGEST_ONLY | ACT_WITH_APPROVAL | AUTONOMOUS)");
        }
        service.setAutonomyOverride(tenantId, agentId, autonomy, jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.noContent().build();
    }

    /**
     * The effective autonomy for an agent + tenant (override ∨ manifest). Consumed by the runtime to
     * enforce per-tenant policy at invoke time. Internal service token or platform admin.
     */
    @GetMapping("/{agentId}/tenants/{tenantId}/autonomy")
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public Map<String, String> effectiveAutonomy(@PathVariable String agentId, @PathVariable UUID tenantId) {
        return Map.of("agentId", agentId, "tenantId", tenantId.toString(),
                "autonomy", service.effectiveAutonomy(tenantId, agentId).name());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID resolveTenant(Jwt jwt, String tenantHeader) {
        String fromJwt = jwt == null ? null : jwt.getClaimAsString("tenant_id");
        if (fromJwt != null && !fromJwt.isBlank()) {
            return UUID.fromString(fromJwt);
        }
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return UUID.fromString(tenantHeader);
        }
        throw new IllegalArgumentException("Cannot resolve tenant: no tenant_id in JWT and no X-Tenant-Id header");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
