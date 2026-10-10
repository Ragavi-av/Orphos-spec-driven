package io.orphos.eip.docingest.access;

import io.orphos.eip.common.http.EipRestTemplates;
import io.orphos.eip.common.access.AccessEnforcementFilter;
import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.HttpAccessGrantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;

/**
 * RBAC enforcement for the Document Search dashboard section: gates the
 * user-facing search endpoint {@code /api/doc-ingest/search} on the
 * {@code doc-search} resource. Document-management + admin paths under
 * {@code /api/doc-ingest} are intentionally NOT mapped here (they belong to the
 * Document Ingestion admin tabs, gated separately). Fails open when the map is
 * unavailable.
 */
@Configuration
public class AccessEnforcementConfig {

    @Value("${eip.security.tenant-discovery.url:${EIP_TENANT_DISCOVERY_URL:}}")
    private String platformCoreUrl;

    @Value("${eip.security.tenant-discovery.service-token:${eip.security.internal-service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    // Opt-in, globally-configured resource keys that FAIL CLOSED when the access
    // map can't be resolved. Empty by default → fail open.
    @Value("${eip.access.fail-closed-resources:}")
    private String failClosedResources;

    private static LinkedHashMap<String, String> routes() {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        // Admin doc tabs (most-specific first).
        r.put("/api/doc-ingest/admin/sources", "doc-sources");
        r.put("/api/doc-ingest/admin/folders", "doc-ingest-defaults");
        r.put("/api/doc-ingest/admin/search/history", "doc-queries");
        r.put("/api/doc-ingest/admin/reindex", "doc-list");
        r.put("/api/doc-ingest/admin/documents", "doc-list");
        // NOTE: /api/doc-ingest/admin/settings is intentionally NOT mapped — one
        // settings doc backs Document AI (ai group), Ingestion Defaults and Search
        // Settings (documents group); mapping it to any one would break the others.
        // It stays gated by the service's coarse role rule.
        // User-facing dashboard + doc-management paths.
        r.put("/api/doc-ingest/search", "doc-search");
        // MUST precede the /documents entry below: resourceFor() prefix-matches
        // in insertion order, so listing /documents first would swallow this via
        // startsWith(p + "/") and gate a one-integer count behind the admin
        // doc-list capability. It answers the dashboard's corpus hint, so it
        // rides on doc-search — whoever may search the corpus may know its size,
        // and revoking search revokes this with it.
        r.put("/api/doc-ingest/documents/count", "doc-search");
        r.put("/api/doc-ingest/documents", "doc-list");
        r.put("/api/doc-ingest/upload", "doc-upload");
        r.put("/api/doc-ingest/url", "doc-upload");
        // NOTE: /api/doc-ingest/extract is intentionally NOT mapped. It stores
        // nothing (OCR + AI summary for the mobile capture review), and mapping it
        // to doc-upload gated every executive's capture behind the admin Doc
        // Ingestion tab. It stays gated by the service's coarse member rule.
        // NOTE: /api/doc-ingest/captures is intentionally NOT mapped either. It is
        // the mobile Save of a user's own note, stamped with their JWT subject and
        // visible only to them in search; gating it on doc-upload silently dropped
        // every non-admin's notes from Document Search.
        return r;
    }

    @Bean
    public AccessGrantSource accessGrantSource(EipRestTemplates restTemplates) {
        return new HttpAccessGrantSource(restTemplates, platformCoreUrl, internalToken);
    }

    @Bean
    public AccessEnforcementFilter accessEnforcementFilter(AccessGrantSource grantSource) {
        return new AccessEnforcementFilter(grantSource, routes(),
                AccessEnforcementFilter.parseResourceSet(failClosedResources));
    }

    @Bean
    public FilterRegistrationBean<AccessEnforcementFilter> accessEnforcementFilterRegistration(
            AccessEnforcementFilter filter) {
        FilterRegistrationBean<AccessEnforcementFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(filter.getOrder());
        return reg;
    }
}
