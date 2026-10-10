package io.orphos.eip.admin.access.controller;

import io.orphos.eip.admin.access.service.AccessControlService;
import io.orphos.eip.admin.common.CurrentUser;
import io.orphos.eip.admin.roles.service.RoleAssignmentGuard;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the per-tenant RBAC access map — which roles may see which admin tabs
 * and user dashboard sections. Powers the "Access Control" tab under
 * Authentication &amp; Authorization.
 *
 * <p>Reads are open to any admin-capable role (so each role's own tab filter can
 * load the map); writes are restricted to the Authentication &amp; Authorization
 * owners (full admin, platform admin, secops admin). The resource catalog itself
 * (groups/tabs/sections) is owned by the SPA — this endpoint stores opaque
 * {@code (role, resourceKey)} grants and returns the guardrail metadata the SPA
 * needs (locked resources + immutable roles).</p>
 */
@RestController
@RequestMapping("/api/admin/access-control")
public class AccessControlController {

    private static final Logger log = LogManager.getLogger(AccessControlController.class);

    private final AccessControlService service;
    private final KeycloakProvisioningClient keycloak;
    private final TenantRepository tenants;
    private final RoleAssignmentGuard roleGuard;

    public AccessControlController(AccessControlService service, KeycloakProvisioningClient keycloak,
                                   TenantRepository tenants, RoleAssignmentGuard roleGuard) {
        this.service = service;
        this.keycloak = keycloak;
        this.tenants = tenants;
        this.roleGuard = roleGuard;
    }

    /**
     * Returns the current tenant's grants plus the metadata the SPA needs to
     * render the matrix: the assignable roles (realm roles minus the immutable
     * ones), the immutable roles, and the locked (platform-only) resource keys.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin','ingestion_admin','data_ai_admin')")
    public ResponseEntity<Map<String, Object>> getAccessMap() {
        List<String> roles = new ArrayList<>();
        String realm = currentRealmOrNull();
        if (realm != null) {
            for (String r : keycloak.listRealmRoleNames(realm)) {
                if (!AccessControlService.IMMUTABLE_ROLES.contains(r)) roles.add(r);
            }
        }
        return ResponseEntity.ok(Map.of(
                "grants", service.listGrants(),
                "roles", roles,
                "immutableRoles", AccessControlService.IMMUTABLE_ROLES,
                "lockedResources", AccessControlService.LOCKED_RESOURCES));
    }

    /** Sets or clears a single grant. Body: {@code {role, resourceKey, effect}}. */
    @PutMapping("/grant")
    @PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
    public ResponseEntity<Map<String, Object>> setGrant(@RequestBody Map<String, Object> body) {
        String role = str(body.get("role"));
        String resourceKey = str(body.get("resourceKey"));
        String effect = str(body.get("effect"));
        // Editing a role's screen access is as privileged as assigning that role:
        // a secops admin can shape the grants of the roles it manages, not of admins.
        if (!role.isBlank()) roleGuard.requireCanManage(role);
        try {
            service.setGrant(role, resourceKey, effect, CurrentUser.key());
            return ResponseEntity.ok(Map.of("role", role, "resourceKey", resourceKey,
                    "effect", effect.isBlank() ? "inherit" : effect));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to set access grant {}:{}={}: {}", role, resourceKey, effect, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Could not save the grant: " + e.getMessage()));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** Current tenant's Keycloak realm, or null if no tenant is in scope. */
    private String currentRealmOrNull() {
        UUID tenantId;
        try {
            tenantId = TenantContextHolder.requireTenantId();
        } catch (Exception e) {
            return null;
        }
        return tenants.findById(tenantId).map(Tenant::keycloakRealm).orElse(null);
    }
}
