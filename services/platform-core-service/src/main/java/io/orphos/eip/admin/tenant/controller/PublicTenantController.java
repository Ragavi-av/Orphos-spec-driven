package io.orphos.eip.admin.tenant.controller;

import io.orphos.eip.admin.tenant.dto.TenantDtos.PublicTenantView;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unauthenticated tenant lookup. The React SPA hits this on first paint to
 * resolve the Keycloak realm name from the request host, BEFORE the Keycloak
 * adapter initialises — otherwise the SPA can't know which {@code /realms/{X}/}
 * to redirect to for login.
 *
 * <p>This endpoint deliberately exposes only the realm name and active status,
 * never the isolation mode or database name. That information is admin-only.</p>
 */
@RestController
@RequestMapping("/api/public/tenants")
public class PublicTenantController {

    private final TenantRepository tenants;

    public PublicTenantController(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/by-domain/{domain}")
    public PublicTenantView byDomain(@PathVariable String domain) {
        return tenants.findByDomain(domain.toLowerCase())
                .map(PublicTenantView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No tenant registered for domain '" + domain + "'."));
    }

    /**
     * Resolves a tenant by URL slug — the URL-decoded {@code displayName} that
     * appears as the first path segment in the SPA's per-tenant URL
     * ({@code http://localhost:3001/<displayName>/}).
     *
     * <p>Match is case-insensitive against {@code displayName}. The path
     * variable is URL-decoded by Spring before reaching us, so a request to
     * {@code /by-slug/Acme%20Corporation} arrives here as
     * {@code "Acme Corporation"}.</p>
     */
    @GetMapping("/by-slug/{slug}")
    public PublicTenantView bySlug(@PathVariable String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tenant slug must not be empty.");
        }
        return tenants.findByDisplayNameIgnoreCase(slug.trim())
                .map(PublicTenantView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No tenant registered with display name '" + slug + "'."));
    }
}
