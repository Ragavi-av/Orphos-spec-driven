package io.orphos.eip.admin.auth.controller;

import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Read-only window onto the current tenant's Keycloak realm authentication
 * configuration — realm login flags, identity providers (SSO), and user
 * federation. Powers the Admin console's "Authentication" tab, which shows
 * these settings as information only.
 *
 * <p>This is intentionally separate from {@link AdminAuthController}: that
 * controller manages the admin-service's own editable auth config store,
 * whereas this endpoint reflects Keycloak's live state and never mutates it.</p>
 *
 * <p>Realm resolution follows the same tenant-context rule as
 * {@link AdminScimController}: the endpoint reads whichever tenant realm is in
 * scope (via the {@code X-Tenant-Id} impersonation header for platform admins),
 * and returns an {@code available:false} envelope when no tenant is selected.</p>
 */
@RestController
@RequestMapping("/api/admin/auth/keycloak")
@PreAuthorize("hasAnyRole('admin', 'platform_admin', 'secops_admin')")
public class KeycloakAuthInfoController {

    private static final Logger log = LogManager.getLogger(KeycloakAuthInfoController.class);

    private final KeycloakProvisioningClient keycloak;
    private final TenantRepository tenants;

    public KeycloakAuthInfoController(KeycloakProvisioningClient keycloak, TenantRepository tenants) {
        this.keycloak = keycloak;
        this.tenants = tenants;
    }

    @GetMapping("/auth-info")
    public ResponseEntity<?> authInfo() {
        String realm = currentRealmOrNull();
        if (realm == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "No tenant in scope. Platform admins must pick a tenant to view its "
                            + "Keycloak authentication settings."));
        }
        try {
            return ResponseEntity.ok(keycloak.getRealmAuthInfo(realm));
        } catch (Exception e) {
            log.warn("Could not read Keycloak auth info for realm '{}': {}", realm, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Could not read this tenant's Keycloak realm settings: " + e.getMessage()));
        }
    }

    /** Current tenant's Keycloak realm name, or null if no tenant is in scope. */
    private String currentRealmOrNull() {
        UUID tenantId;
        try {
            tenantId = TenantContextHolder.requireTenantId();
        } catch (Exception e) {
            return null;
        }
        return tenants.findById(tenantId)
                .map(Tenant::keycloakRealm)
                .orElse(null);
    }
}
