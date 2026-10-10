package io.orphos.eip.docingest.settings;

import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints for per-tenant ingestion + search settings.
 *
 * <p>Gated to {@code admin}, {@code platform_admin} and the scoped
 * {@code ingestion_admin} role (the latter two reach this via the role
 * hierarchy / explicit grant). Tenant is resolved from
 * {@link TenantContextHolder}, honouring a platform_admin's {@code X-Tenant-Id}
 * acting-tenant header.</p>
 *
 * <ul>
 *   <li>GET    /api/doc-ingest/admin/settings — current settings (or defaults)</li>
 *   <li>PUT    /api/doc-ingest/admin/settings — upsert settings</li>
 *   <li>DELETE /api/doc-ingest/admin/settings — reset to platform defaults</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/doc-ingest/admin/settings")
@PreAuthorize("hasAnyRole('admin','platform_admin','ingestion_admin')")
public class DocSettingsController {

    private final DocSettingsService service;

    public DocSettingsController(DocSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public DocSettings get() {
        return service.get(currentTenantId());
    }

    @PutMapping
    public DocSettings save(@RequestBody DocSettings req, @AuthenticationPrincipal Jwt jwt) {
        return service.save(currentTenantId(), req, displayName(jwt));
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        service.reset(currentTenantId());
        return ResponseEntity.noContent().build();
    }

    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String displayName(Jwt jwt) {
        if (jwt == null) return "";
        for (String claim : new String[]{"preferred_username", "email"}) {
            String v = jwt.getClaimAsString(claim);
            if (v != null && !v.isBlank()) return v;
        }
        String sub = jwt.getSubject();
        return sub == null ? "" : sub;
    }
}
