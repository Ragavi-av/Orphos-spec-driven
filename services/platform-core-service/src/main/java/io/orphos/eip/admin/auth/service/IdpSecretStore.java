package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.secrets.SecretRefService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Moves IdentityProvider config secrets out of ArcadeDB and into OpenBao.
 *
 * <p>Before persisting an {@code IdentityProvider} document, this store
 * scans its {@code config} map for known-secret keys and replaces each
 * raw value with an opaque {@code vault:...} reference. The reference is
 * what ends up in ArcadeDB; the secret itself only ever lives in OpenBao.</p>
 *
 * <p>The reverse pass ({@link #resolveAll(Map)}) is used at runtime by
 * provider implementations (OIDC token exchange, LDAP manager-bind, SAML
 * signing) when they need the actual secret to make an outbound call.</p>
 *
 * <p>Operator hand-edited configs that already contain {@code vault:...}
 * refs are passed through untouched - this lets administrators paste in
 * a long-lived secret reference they manage outside the UI.</p>
 */
@Component
public class IdpSecretStore {

    /**
     * Keys that should never live in ArcadeDB. Adding a new secret-bearing
     * key here is the only change needed to bring it under OpenBao.
     */
    public static final Set<String> SECRET_KEYS = Set.of(
            "clientSecret",
            "managerPassword",
            "privateKey",
            "signingKey"
    );

    private final SecretRefService secrets;

    public IdpSecretStore(SecretRefService secrets) {
        this.secrets = secrets;
    }

    /**
     * Returns a copy of {@code config} with every {@link #SECRET_KEYS} value
     * replaced by a vault reference. The original map is not mutated.
     */
    public Map<String, Object> extractAndStore(String providerName, Map<String, Object> config) {
        if (config == null || config.isEmpty()) return Map.of();
        // Preserve insertion order so re-saving a provider doesn't randomly
        // shuffle its config in the admin UI.
        Map<String, Object> out = new LinkedHashMap<>(config);
        for (String key : SECRET_KEYS) {
            Object raw = out.get(key);
            if (!(raw instanceof String s) || s.isBlank()) continue;
            if (SecretRefService.isManaged(s)) {
                // Already a vault ref - operator pasted it in directly. Leave it.
                continue;
            }
            String ref = secrets.store("idp/" + safeName(providerName) + "/" + key, "value", s);
            out.put(key, ref);
        }
        return out;
    }

    /**
     * Returns a copy of {@code config} with vault refs resolved to plain
     * values. Refs that fail to resolve are removed entirely - callers
     * should fail closed when a required secret is missing.
     */
    public Map<String, Object> resolveAll(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return Map.of();
        Map<String, Object> out = new HashMap<>(config);
        for (String key : SECRET_KEYS) {
            Object v = out.get(key);
            if (v instanceof String s && SecretRefService.isManaged(s)) {
                secrets.resolve(s).ifPresentOrElse(
                        plain -> out.put(key, plain),
                        () -> out.remove(key));
            }
        }
        return out;
    }

    /**
     * Permanently delete every vault entry referenced by the given config map.
     * Used when an IdentityProvider record is removed, so we don't leave
     * orphaned secrets in OpenBao.
     */
    public void purge(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return;
        for (String key : SECRET_KEYS) {
            Object v = config.get(key);
            if (v instanceof String s && SecretRefService.isManaged(s)) {
                secrets.delete(s);
            }
        }
    }

    private static String safeName(String n) {
        if (n == null) return "unknown";
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            b.append((Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        return b.length() == 0 ? "unknown" : b.toString();
    }
}
