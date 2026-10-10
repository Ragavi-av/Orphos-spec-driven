package io.orphos.eip.nlquery.component;

import io.orphos.eip.secrets.SecretsClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-side store for per-user, per-provider API keys.
 *
 * Keys are scoped by the owning tenant, the user's Keycloak subject (the JWT
 * 'sub' claim) and the provider id. They are stored in OpenBao under the path
 * {@code tenants/{tenantId}/users/{sub}/{providerId}} with a single field
 * {@code api_key}.
 *
 * <p>The {@code tenantId} prefix is required for multi-tenant isolation: a
 * single Keycloak subject can exist in more than one tenant's realm, so a
 * tenant-less {@code users/{sub}/...} path would let a user's personal key
 * leak across every tenant they belong to. Re-keying with the tenant UUID
 * keeps each tenant's copy of a user's key strictly separate.</p>
 *
 * The store is intentionally write-only from the outside world's perspective:
 * callers may probe whether a key is configured but never read it back through
 * a REST endpoint. Only the chat providers read the raw value, server-side.
 */
@Component
public class UserApiKeyStore {

    static final String FIELD = "api_key";

    private final SecretsClient vault;

    public UserApiKeyStore(SecretsClient vault) {
        this.vault = vault;
    }

    public boolean isBackendConfigured() {
        return vault.isConfigured();
    }

    public Optional<String> readKey(UUID tenantId, String userSub, String providerId) {
        if (tenantId == null || userSub == null || userSub.isBlank()
                || providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return vault.readField(path(tenantId, userSub, providerId), FIELD);
    }

    public boolean isConfigured(UUID tenantId, String userSub, String providerId) {
        return readKey(tenantId, userSub, providerId).isPresent();
    }

    public void writeKey(UUID tenantId, String userSub, String providerId, String apiKey) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (userSub == null || userSub.isBlank()) {
            throw new IllegalArgumentException("userSub is required");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        vault.writeField(path(tenantId, userSub, providerId), FIELD, apiKey.trim());
    }

    public void deleteKey(UUID tenantId, String userSub, String providerId) {
        if (tenantId == null || userSub == null || userSub.isBlank()
                || providerId == null || providerId.isBlank()) return;
        vault.delete(path(tenantId, userSub, providerId));
    }

    private String path(UUID tenantId, String userSub, String providerId) {
        // Sub claims from Keycloak are UUIDs but we still sanitise defensively to keep
        // the secret path well-formed. The tenant UUID prefix isolates each tenant's
        // copy of a user's key (see class javadoc).
        return "tenants/" + tenantId + "/users/" + sanitise(userSub) + "/" + sanitise(providerId);
    }

    private String sanitise(String s) {
        return s.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }
}
