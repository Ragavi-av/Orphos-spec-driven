package io.orphos.eip.registry.controller;

import io.orphos.eip.registry.vo.PluginManifestDto;
import io.orphos.eip.registry.vo.RegisterPluginRequest;
import io.orphos.eip.registry.service.PluginRegistryService;
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

@RestController
@RequestMapping("/api/platform/plugins")
public class PluginRegistryController {

    private final PluginRegistryService service;

    public PluginRegistryController(PluginRegistryService service) {
        this.service = service;
    }

    /** All registered plugins — platform admin only. */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public List<PluginManifestDto> listAll() {
        return service.findAll();
    }

    /**
     * Active plugins for the calling tenant — used by the SPA on login to
     * know which remotes to load and which nav tabs to show.
     */
    @GetMapping("/active")
    public List<PluginManifestDto> listActive(@AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        UUID tenantId = resolveTenant(jwt, tenantHeader);
        return service.findActiveForTenant(tenantId);
    }

    /**
     * Point check consumed by the shell's nginx {@code auth_request} stage
     * (see frontend/shell/nginx.conf's {@code /_plugin_gate/<id>} locations) as
     * a ForwardAuth-style access boundary — returns 200 if the plugin is enabled
     * for the tenant, 403 otherwise. No body needed. The caller's JWT and
     * X-Tenant-Id are forwarded by nginx, so tenant resolution matches the other
     * endpoints here.
     */
    @GetMapping("/{pluginId}/gate")
    public ResponseEntity<Void> gate(@PathVariable String pluginId,
                                     @AuthenticationPrincipal Jwt jwt,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        UUID tenantId = resolveTenant(jwt, tenantHeader);
        boolean ok = service.isEnabled(tenantId, pluginId);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.status(403).build();
    }

    /**
     * Plugin self-registration — called by each plugin service at startup.
     * Requires an internal service token (ROLE_service_internal).
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public PluginManifestDto register(@Valid @RequestBody RegisterPluginRequest req) {
        return service.register(req);
    }

    /** Enable a plugin for a tenant — platform admin only. */
    @PutMapping("/{pluginId}/tenants/{tenantId}/enabled")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Void> enable(@PathVariable String pluginId,
                                       @PathVariable UUID tenantId,
                                       @RequestBody Map<String, Boolean> body,
                                       @AuthenticationPrincipal Jwt jwt) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String actor = jwt.getClaimAsString("preferred_username");
        service.setEnabled(tenantId, pluginId, enabled, actor);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID resolveTenant(Jwt jwt, String tenantHeader) {
        // Platform admins pass X-Tenant-Id header (no tenant_id in their JWT).
        // Tenant users carry tenant_id in their JWT; header is ignored.
        String fromJwt = jwt.getClaimAsString("tenant_id");
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
