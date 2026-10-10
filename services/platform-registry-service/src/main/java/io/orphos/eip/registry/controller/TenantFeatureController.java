package io.orphos.eip.registry.controller;

import io.orphos.eip.registry.entity.TenantFeature;
import io.orphos.eip.registry.repository.TenantFeatureRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tenant gates for platform <em>functionalities</em> that are not plugins.
 *
 * <p>{@link PluginRegistryController} already gates Module-Federation UI plugins
 * per tenant. A functionality like voice is not a plugin — it has no remote and
 * no nav tab; it is a capability that several existing surfaces light up when it
 * is on (the Ask Orphos composer grows a dictation gesture, doc-ingest starts
 * transcribing audio uploads). It still needs the same per-tenant switch, so it
 * reuses the same {@code TenantFeature} store under a namespaced key.</p>
 *
 * <p>Keys are prefixed with {@value #KEY_PREFIX} so a functionality can never
 * collide with a pluginId in the shared {@code (tenantId, pluginId)} index —
 * the same convention {@code AgentRegistry} and {@code McpServerRegistry} use
 * for their own rows.</p>
 *
 * <p><b>Default is OFF.</b> Plugins default to enabled-unless-disabled because
 * they ship with the product; a functionality that costs a 3 GB speech model and
 * routes a user's voice through a recogniser is the opposite case, and must be a
 * deliberate act by a platform admin for a named tenant.</p>
 */
@RestController
@RequestMapping("/api/platform/features")
public class TenantFeatureController {

    /** Namespace for functionality rows in the shared TenantFeature store. */
    public static final String KEY_PREFIX = "feature:";

    /**
     * Functionalities that may be gated here. An allow-list rather than a free
     * string: these keys become durable rows, and a typo would otherwise create
     * a gate that silently never matches the one the reader is checking.
     */
    private static final Set<String> KNOWN_FEATURES = Set.of("voice");

    private final TenantFeatureRepository features;

    public TenantFeatureController(TenantFeatureRepository features) {
        this.features = features;
    }

    /** Tenants this functionality is currently enabled for — platform admin only. */
    @GetMapping("/{featureId}/tenants")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public List<String> enabledTenants(@PathVariable String featureId) {
        String key = key(featureId);
        return features.findAllEnabled().stream()
                .filter(tf -> key.equals(tf.getPluginId()))
                .map(tf -> tf.getTenantId().toString())
                .toList();
    }

    /** Enable or disable this functionality for one tenant — platform admin only. */
    @PutMapping("/{featureId}/tenants/{tenantId}/enabled")
    @PreAuthorize("hasAuthority('ROLE_platform_admin')")
    public ResponseEntity<Void> setEnabled(@PathVariable String featureId,
                                           @PathVariable UUID tenantId,
                                           @RequestBody Map<String, Boolean> body,
                                           @AuthenticationPrincipal Jwt jwt) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String actor = jwt.getClaimAsString("preferred_username");
        features.save(new TenantFeature(tenantId, key(featureId), enabled, actor));
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether this functionality is on for the CALLING tenant.
     *
     * <p>Readable by any authenticated user — it reveals nothing beyond whether a
     * control should be drawn, and voice-service calls it on the user's behalf on
     * every status check. Same tenant resolution as the plugin endpoints: the JWT
     * claim wins, and the header only serves platform admins acting on a tenant.</p>
     */
    @GetMapping("/{featureId}/enabled")
    public Map<String, Object> enabledForCaller(@PathVariable String featureId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        String key = key(featureId);
        UUID tenantId = resolveTenant(jwt, tenantHeader);
        boolean enabled = features.findByTenantIdAndPluginId(tenantId, key)
                .map(TenantFeature::isEnabled)
                .orElse(false);   // default OFF — see the class note
        return Map.of("feature", featureId, "tenantId", tenantId.toString(), "enabled", enabled);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String key(String featureId) {
        String id = featureId == null ? "" : featureId.trim().toLowerCase();
        if (!KNOWN_FEATURES.contains(id)) {
            throw new IllegalArgumentException("Unknown platform functionality: " + featureId);
        }
        return KEY_PREFIX + id;
    }

    private UUID resolveTenant(Jwt jwt, String tenantHeader) {
        String fromJwt = jwt.getClaimAsString("tenant_id");
        if (fromJwt != null && !fromJwt.isBlank()) {
            return UUID.fromString(fromJwt);
        }
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            return UUID.fromString(tenantHeader);
        }
        throw new IllegalArgumentException("Cannot resolve tenant: no tenant_id in JWT and no X-Tenant-Id header");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
