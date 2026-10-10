package io.orphos.eip.admin.tenant.controller;

import io.orphos.eip.admin.tenant.dto.TenantDtos;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.common.security.AllowedIssuersProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service-to-service discovery endpoint. Returns the canonical list of
 * issuer URLs admin-service currently trusts. Every non-admin service polls
 * this on a short interval so a new tenant becomes trusted everywhere without
 * a config push.
 *
 * <p><b>Network exposure:</b> permit-all in admin-service's SecurityConfig but
 * intentionally placed under {@code /api/internal/} to signal that production
 * deployments should restrict it via network policy (e.g. only from the
 * service mesh / internal subnet). The data is low-sensitivity (a list of
 * Keycloak realm URLs — the realm names are also visible in any JWT's
 * {@code iss} claim) but enumerating tenants is still a recon step worth
 * blocking from the public internet.</p>
 *
 * <p>TODO: add a shared-secret header check ({@code X-Service-Token}) wired
 * via OpenBao for defence-in-depth once we move beyond docker-compose dev.</p>
 */
@RestController
@RequestMapping("/api/internal")
public class InternalTenantIssuersController {

    private final AllowedIssuersProvider provider;
    private final TenantRepository tenants;

    public InternalTenantIssuersController(AllowedIssuersProvider provider, TenantRepository tenants) {
        this.provider = provider;
        this.tenants = tenants;
    }

    /** Trusted-issuer URLs for multi-issuer JWT validation. */
    @GetMapping("/tenant-issuers")
    public List<String> tenantIssuers() {
        return new ArrayList<>(provider.allowedIssuers());
    }

    /**
     * Active tenant UUIDs for background jobs in other services. Returned as
     * strings so callers don't have to share a UUID JSON convention. Used by
     * priority-engine's scheduled refresh, briefing's daily cron, okg-reasoning's
     * inference cycle, etc.
     */
    @GetMapping("/active-tenants")
    public List<String> activeTenants() {
        return tenants.listActive().stream()
                .filter(t -> t.status() == TenantStatus.ACTIVE)
                .map(Tenant::id)
                .map(java.util.UUID::toString)
                .toList();
    }

    /**
     * Full tenant info by UUID. Used by services that need to make routing
     * decisions based on a tenant's isolation mode — currently graph-proxy
     * (per-tenant Fuseki dataset vs named-graph rewriting), and any future
     * service that needs to know the {@code arcadeDatabase} / {@code keycloakRealm}
     * pair.
     *
     * <p>404 when the tenant is unknown OR soft-deleted; callers MUST treat
     * not-found as "deny" rather than "fall through to a shared default" to
     * prevent cross-tenant data leakage on a stale cache.</p>
     */
    @GetMapping("/tenants/{id}")
    public TenantDtos.TenantView tenantInfo(@PathVariable UUID id) {
        return tenants.findById(id)
                .map(TenantDtos.TenantView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Tenant not found: " + id));
    }
}
