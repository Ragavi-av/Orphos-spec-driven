package io.orphos.eip.registry.controller;

import io.orphos.eip.agent.spec.McpServerRegistration;
import io.orphos.eip.registry.vo.McpServerSummaryDto;
import io.orphos.eip.registry.service.McpServerRegistry;
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
 * MCP tool-server registry endpoints (TO-BE design §6). Mirrors {@code PluginRegistryController}.
 */
@RestController
@RequestMapping("/api/platform/mcp-servers")
public class McpServerController {

    private final McpServerRegistry service;

    public McpServerController(McpServerRegistry service) {
        this.service = service;
    }

    /** All registered MCP servers — platform admin only (inventory + heartbeat status). */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public List<McpServerSummaryDto> listAll() {
        return service.findAll();
    }

    /** MCP servers live for the calling tenant — consumed by the orchestrator to resolve tool names. */
    @GetMapping("/active")
    public List<McpServerSummaryDto> listActive(@AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        return service.findActiveForTenant(resolveTenant(jwt, tenantHeader));
    }

    /** MCP-server self-registration — called by each server at startup. Internal service token required. */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public McpServerSummaryDto register(@Valid @RequestBody McpServerRegistration req) {
        return service.register(req);
    }

    /** Enable/disable an MCP server for a tenant — platform admin only. */
    @PutMapping("/{serverId}/tenants/{tenantId}/enabled")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Void> enable(@PathVariable String serverId,
                                       @PathVariable UUID tenantId,
                                       @RequestBody Map<String, Boolean> body,
                                       @AuthenticationPrincipal Jwt jwt) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        service.setEnabled(tenantId, serverId, enabled, jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.noContent().build();
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
